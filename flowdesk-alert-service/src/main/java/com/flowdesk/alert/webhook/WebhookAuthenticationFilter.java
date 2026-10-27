package com.flowdesk.alert.webhook;
import com.flowdesk.alert.mapper.WebhookIntegrationMapper;
import com.flowdesk.alert.model.*;

import com.flowdesk.alert.service.AlertIdempotencyCodec;
import com.flowdesk.common.web.ApiErrorWriter;
import com.flowdesk.common.web.FlowDeskRequestAttributes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 监控告警入口的认证过滤器，把尚未可信的 HTTP 请求转换为已验签的请求快照。
 *
 * <p>先限制请求方法、请求头和报文大小，再用 API key 查找集成候选，逐一核对凭据、HMAC 签名和时间窗口。
 * 签名覆盖收到的原始字节，不能先解析 JSON 或重新序列化。所有检查通过后，只把 {@link VerifiedWebhook}
 * 放入当前请求属性；控制器随后解析业务字段，接纳服务再通过事务裁决 nonce、事件唯一性和告警状态。
 *
 * <p>本过滤器不建立业务租户上下文，也不记录 nonce。认证失败时直接写入错误响应并终止过滤器链；
 * 集成不存在、已停用、凭据错误或密钥缺失均使用同一个认证错误，避免暴露集成注册情况。
 * 请求形式错误按 400、405、413 或 415 返回，认证及时间窗错误使用 401；数据库或密码算法等服务端异常向上传播。
 */
public final class WebhookAuthenticationFilter extends OncePerRequestFilter {
    public static final String PATH = "/api/v1/webhooks/monitoring/alerts";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    // 这五个安全头必须各出现一次；拒绝重复值，避免代理、Servlet 和签名方对同一请求作出不同解释。
    private static final Set<String> SECURITY_HEADERS =
            Set.of(
                    "X-FlowDesk-Api-Key",
                    "X-FlowDesk-Signature-Version",
                    "X-FlowDesk-Timestamp",
                    "X-FlowDesk-Nonce",
                    "X-FlowDesk-Signature");
    private static final Pattern TIMESTAMP = Pattern.compile("0|[1-9][0-9]{0,18}");
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    private static final Pattern SIGNATURE = Pattern.compile("sha256=[0-9a-f]{64}");
    private static final Pattern API_KEY =
            Pattern.compile("fdak1\\.([A-Za-z0-9_-]{22})\\.([A-Za-z0-9_-]{43})");

    private final WebhookIntegrationMapper integrations;
    private final WebhookSecretRegistry secrets;
    private final WebhookProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    /**
     * 组装当前组件依赖并委托完整构造方法；使用 UTC 系统时钟等默认运行配置，显式依赖版本可用于确定性测试。
     */
    public WebhookAuthenticationFilter(
            WebhookIntegrationMapper integrations,
            WebhookSecretRegistry secrets,
            WebhookProperties properties,
            ObjectMapper json) {
        this(integrations, secrets, properties, json, Clock.systemUTC());
    }

    /**
     * 注入集成候选查询、签名秘密注册表、请求窗口配置及可替换时钟，供同一请求的认证检查使用。
     */
    WebhookAuthenticationFilter(
            WebhookIntegrationMapper integrations,
            WebhookSecretRegistry secrets,
            WebhookProperties properties,
            ObjectMapper json,
            Clock clock) {
        this.integrations = integrations;
        this.secrets = secrets;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    /**
     * 仅对固定监控告警路径启用 Webhook 认证，其余请求交回各自的安全过滤链。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只处理固定的监控告警路径；其他接口继续使用各自的身份认证流程。
        return !PATH.equals(request.getRequestURI());
    }

    /**
     * 依次检查请求形式、报文大小、API key、HMAC 及时间窗口；成功后保存已验签快照，失败时写错误响应并终止链路。
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID correlation = correlation(request);
        // 先检查传输形式：不接受查询参数、压缩报文或混入的登录身份，只接受显式 UTF-8 的 JSON POST。
        if (request.getQueryString() != null) {
            reject(response, 400, "WEBHOOK_QUERY_FORBIDDEN", correlation);
            return;
        }
        if (!"POST".equals(request.getMethod())) {
            response.setHeader("Allow", "POST");
            reject(response, 405, "METHOD_NOT_ALLOWED", correlation);
            return;
        }
        if (!contentTypeAccepted(request.getContentType())) {
            reject(response, 415, "MEDIA_TYPE_UNSUPPORTED", correlation);
            return;
        }
        List<String> encodings = values(request.getHeaders("Content-Encoding"));
        if (!encodings.isEmpty()
                && (encodings.size() != 1 || !"identity".equalsIgnoreCase(encodings.get(0)))) {
            reject(response, 415, "CONTENT_ENCODING_UNSUPPORTED", correlation);
            return;
        }
        if (!values(request.getHeaders("Authorization")).isEmpty()
                || !values(request.getHeaders("Cookie")).isEmpty()) {
            reject(response, 400, "WEBHOOK_HEADER_MISSING", correlation);
            return;
        }
        for (String header : SECURITY_HEADERS) {
            List<String> values = values(request.getHeaders(header));
            if (values.size() != 1 || values.get(0) == null || values.get(0).isEmpty()) {
                reject(response, 400, "WEBHOOK_HEADER_MISSING", correlation);
                return;
            }
        }
        String version = request.getHeader("X-FlowDesk-Signature-Version");
        String timestamp = request.getHeader("X-FlowDesk-Timestamp");
        String nonce = request.getHeader("X-FlowDesk-Nonce");
        String signature = request.getHeader("X-FlowDesk-Signature");
        // 此处只校验头部语法；时间是否过期要等身份与签名确认后判断，nonce 是否重复由数据库裁决。
        if (!"1".equals(version)) {
            reject(response, 401, "WEBHOOK_SIGNATURE_VERSION_UNSUPPORTED", correlation);
            return;
        }
        if (!TIMESTAMP.matcher(timestamp).matches()) {
            reject(response, 400, "WEBHOOK_TIMESTAMP_INVALID", correlation);
            return;
        }
        if (!NONCE.matcher(nonce).matches()) {
            reject(response, 400, "WEBHOOK_NONCE_INVALID", correlation);
            return;
        }
        if (!SIGNATURE.matcher(signature).matches()) {
            reject(response, 401, "WEBHOOK_AUTHENTICATION_FAILED", correlation);
            return;
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_BYTES) {
            reject(response, 413, "REQUEST_TOO_LARGE", correlation);
            return;
        }
        // 最多读取 64 KiB 再多一字节，覆盖未声明或错误声明 Content-Length 的超大请求。
        // 保留原始字节用于验签及后续解析；这里消耗输入流后，控制器必须读取快照而不能再次读取请求流。
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            reject(response, 413, "REQUEST_TOO_LARGE", correlation);
            return;
        }
        ApiKeyParts apiKey = apiKey(request.getHeader("X-FlowDesk-Api-Key"));
        // selector 只是查找线索，不能据此信任 tenantId；候选仍必须逐一通过后面的完整认证。
        byte[] selectorHash = AlertIdempotencyCodec.sha256(apiKey.selector());
        List<WebhookIntegrationCandidate> candidates =
                integrations.findCandidates(selectorHash);
        byte[] signing = signingBytes(version, timestamp, nonce, body);
        byte[] supplied = HexFormat.of().parseHex(signature.substring("sha256=".length()));
        Instant now = clock.instant();
        List<WebhookIntegrationCandidate> passing = new ArrayList<>();
        // 没有候选或没有真实密钥时仍计算虚拟 HMAC，减少直接跳过密码计算造成的时序差异。
        // 非短路 & 让候选的各项检查都执行；摘要和签名通过 MessageDigest.isEqual 比较。
        if (candidates.isEmpty()) hmac(secrets.dummySecret(), signing);
        for (WebhookIntegrationCandidate candidate : candidates) {
            byte[] secret =
                    secrets.secret(candidate.integrationId()).orElseGet(secrets::dummySecret);
            boolean pass =
                    apiKey.valid()
                            & "ACTIVE".equals(candidate.status())
                            & "FD_API_KEY_SHA256_V1".equals(candidate.verifierScheme())
                            & candidate.signingVersion() == 1
                            & !now.isBefore(candidate.validFrom())
                            & (candidate.validUntil() == null
                                    || now.isBefore(candidate.validUntil()))
                            & MessageDigest.isEqual(
                                    AlertIdempotencyCodec.sha256(apiKey.verifier()),
                                    candidate.apiKeyVerifier())
                            & MessageDigest.isEqual(hmac(secret, signing), supplied)
                            & secrets.secret(candidate.integrationId()).isPresent();
            if (pass) passing.add(candidate);
        }
        // 只接受唯一通过全部检查的候选。零个表示认证失败，多个表示身份存在歧义，都不能选取第一条继续。
        if (passing.size() != 1) {
            reject(response, 401, "WEBHOOK_AUTHENTICATION_FAILED", correlation);
            return;
        }
        final long epoch;
        try {
            epoch = Long.parseLong(timestamp);
        } catch (NumberFormatException invalid) {
            reject(response, 400, "WEBHOOK_TIMESTAMP_INVALID", correlation);
            return;
        }
        // 请求时间与服务器当前时间的绝对差不能超过配置窗口，过早或过晚都拒绝；运算溢出也按超时处理。
        // 时间窗只限制请求新鲜度，不能替代 nonce 防重放；后者在接纳事务中与事件写入一起提交或回滚。
        long delta;
        try {
            delta = Math.abs(Math.subtractExact(now.getEpochSecond(), epoch));
        } catch (ArithmeticException overflow) {
            delta = Long.MAX_VALUE;
        }
        if (delta > properties.getTimestampWindow().toSeconds()) {
            reject(response, 401, "WEBHOOK_TIMESTAMP_EXPIRED", correlation);
            return;
        }
        WebhookIntegrationCandidate candidate = passing.get(0);
        // 传递的是认证事实和原始报文，不是已接纳的业务事件，也不在此处打开 TenantContextScope。
        request.setAttribute(
                WebhookRequestAttributes.VERIFIED,
                new VerifiedWebhook(
                        candidate.tenantId(),
                        candidate.integrationId(),
                        candidate.integrationCode(),
                        candidate.integrationName(),
                        timestamp,
                        Instant.ofEpochSecond(epoch),
                        nonce,
                        correlation,
                        body,
                        AlertIdempotencyCodec.sha256(body)));
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }

    /** 控制器从当前请求取得认证结果；缺失时直接报错，防止绕过过滤器后继续执行业务。 */
    public static VerifiedWebhook verified(HttpServletRequest request) {
        Object value = request.getAttribute(WebhookRequestAttributes.VERIFIED);
        if (!(value instanceof VerifiedWebhook verified)) {
            throw new IllegalStateException("Webhook authentication boundary was bypassed");
        }
        return verified;
    }

    /** 拆出 16 字节的查找段和 32 字节的凭据段；格式错误也生成占位数据，沿统一认证失败路径处理。 */
    private static ApiKeyParts apiKey(String raw) {
        var matcher = API_KEY.matcher(raw == null ? "" : raw);
        if (!matcher.matches()) {
            byte[] dummy =
                    AlertIdempotencyCodec.sha256(
                            (raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8));
            return new ApiKeyParts(false, java.util.Arrays.copyOf(dummy, 16), dummy);
        }
        try {
            byte[] selector = Base64.getUrlDecoder().decode(matcher.group(1) + "==");
            byte[] verifier = Base64.getUrlDecoder().decode(matcher.group(2) + "=");
            return new ApiKeyParts(
                    selector.length == 16 && verifier.length == 32, selector, verifier);
        } catch (IllegalArgumentException invalid) {
            return new ApiKeyParts(false, new byte[16], new byte[32]);
        }
    }

    /**
     * 按协议拼接签名输入：版本、时间戳、nonce、POST 和固定路径逐行排列，最后紧接原始报文。
     *
     * <p>报文中的空格、换行和字段顺序都属于被签名的字节；不能用解析后的 JSON 替换它们。
     */
    private static byte[] signingBytes(
            String version, String timestamp, String nonce, byte[] body) {
        byte[] prefix =
                (version + "\n" + timestamp + "\n" + nonce + "\nPOST\n" + PATH + "\n")
                        .getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream value = new ByteArrayOutputStream(prefix.length + body.length);
        value.writeBytes(prefix);
        value.writeBytes(body);
        return value.toByteArray();
    }

    /** 使用集成独立的 HMAC-SHA256 密钥计算签名；算法初始化失败属于服务端异常，不能作为认证成功继续。 */
    private static byte[] hmac(byte[] secret, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 只接受带显式 UTF-8 charset 的 application/json；缺失、不可解析或其他字符集均返回不支持。 */
    private static boolean contentTypeAccepted(String raw) {
        try {
            MediaType type = MediaType.parseMediaType(raw == null ? "" : raw);
            return MediaType.APPLICATION_JSON.includes(type)
                    && type.getCharset() != null
                    && StandardCharsets.UTF_8.equals(type.getCharset());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /** 使用统一错误信封返回状态码和关联编号，不回显 API key、签名、密钥或原始报文。 */
    private void reject(HttpServletResponse response, int status, String code, UUID correlation)
            throws IOException {
        ApiErrorWriter.write(
                response, status, code, "Webhook 请求未被接纳。", false, correlation, List.of(), json);
    }

    /** 优先沿用前置关联编号过滤器的结果；独立调用时生成编号，使拒绝响应仍可追踪。 */
    private static UUID correlation(HttpServletRequest request) {
        Object value = request.getAttribute(FlowDeskRequestAttributes.CORRELATION_ID);
        return value instanceof UUID uuid ? uuid : UUID.randomUUID();
    }

    /**
     * 完整收集同名 HTTP 头的所有值，供调用方识别重复安全头，避免只取首个值造成解释歧义。
     */
    private static List<String> values(Enumeration<String> source) {
        if (source == null) return List.of();
        List<String> result = new ArrayList<>();
        while (source.hasMoreElements()) result.add(source.nextElement());
        return result;
    }

    /** 仅供认证计算使用的拆分结果；valid=false 的占位数据不会被接受为集成身份。 */
    private record ApiKeyParts(boolean valid, byte[] selector, byte[] verifier) {}
}
