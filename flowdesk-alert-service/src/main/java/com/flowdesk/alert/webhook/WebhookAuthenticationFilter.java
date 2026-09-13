package com.flowdesk.alert.webhook;

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

/** Full API-049 authentication boundary; it never installs TenantContext. */
public final class WebhookAuthenticationFilter extends OncePerRequestFilter {
    public static final String PATH = "/api/v1/webhooks/monitoring/alerts";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Set<String> SECURITY_HEADERS = Set.of(
            "X-FlowDesk-Api-Key", "X-FlowDesk-Signature-Version", "X-FlowDesk-Timestamp",
            "X-FlowDesk-Nonce", "X-FlowDesk-Signature");
    private static final Pattern TIMESTAMP = Pattern.compile("0|[1-9][0-9]{0,18}");
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    private static final Pattern SIGNATURE = Pattern.compile("sha256=[0-9a-f]{64}");
    private static final Pattern API_KEY = Pattern.compile(
            "fdak1\\.([A-Za-z0-9_-]{22})\\.([A-Za-z0-9_-]{43})");

    private final WebhookIntegrationRepository integrations;
    private final WebhookSecretRegistry secrets;
    private final WebhookProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    public WebhookAuthenticationFilter(WebhookIntegrationRepository integrations,
                                       WebhookSecretRegistry secrets,
                                       WebhookProperties properties,
                                       ObjectMapper json) {
        this(integrations, secrets, properties, json, Clock.systemUTC());
    }

    WebhookAuthenticationFilter(WebhookIntegrationRepository integrations,
                                WebhookSecretRegistry secrets, WebhookProperties properties,
                                ObjectMapper json, Clock clock) {
        this.integrations = integrations; this.secrets = secrets; this.properties = properties;
        this.json = json; this.clock = clock;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH.equals(request.getRequestURI());
    }

    @Override protected void doFilterInternal(HttpServletRequest request,
                                               HttpServletResponse response,
                                               FilterChain chain)
            throws ServletException, IOException {
        UUID correlation = correlation(request);
        if (request.getQueryString() != null) {
            reject(response, 400, "WEBHOOK_QUERY_FORBIDDEN", correlation); return;
        }
        if (!"POST".equals(request.getMethod())) {
            response.setHeader("Allow", "POST");
            reject(response, 405, "METHOD_NOT_ALLOWED", correlation); return;
        }
        if (!contentTypeAccepted(request.getContentType())) {
            reject(response, 415, "MEDIA_TYPE_UNSUPPORTED", correlation); return;
        }
        List<String> encodings = values(request.getHeaders("Content-Encoding"));
        if (!encodings.isEmpty() && (encodings.size() != 1
                || !"identity".equalsIgnoreCase(encodings.get(0)))) {
            reject(response, 415, "CONTENT_ENCODING_UNSUPPORTED", correlation); return;
        }
        if (!values(request.getHeaders("Authorization")).isEmpty()
                || !values(request.getHeaders("Cookie")).isEmpty()) {
            reject(response, 400, "WEBHOOK_HEADER_MISSING", correlation); return;
        }
        for (String header : SECURITY_HEADERS) {
            List<String> values = values(request.getHeaders(header));
            if (values.size() != 1 || values.get(0) == null || values.get(0).isEmpty()) {
                reject(response, 400, "WEBHOOK_HEADER_MISSING", correlation); return;
            }
        }
        String version = request.getHeader("X-FlowDesk-Signature-Version");
        String timestamp = request.getHeader("X-FlowDesk-Timestamp");
        String nonce = request.getHeader("X-FlowDesk-Nonce");
        String signature = request.getHeader("X-FlowDesk-Signature");
        if (!"1".equals(version)) {
            reject(response, 401, "WEBHOOK_SIGNATURE_VERSION_UNSUPPORTED", correlation); return;
        }
        if (!TIMESTAMP.matcher(timestamp).matches()) {
            reject(response, 400, "WEBHOOK_TIMESTAMP_INVALID", correlation); return;
        }
        if (!NONCE.matcher(nonce).matches()) {
            reject(response, 400, "WEBHOOK_NONCE_INVALID", correlation); return;
        }
        if (!SIGNATURE.matcher(signature).matches()) {
            reject(response, 401, "WEBHOOK_AUTHENTICATION_FAILED", correlation); return;
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_BYTES) {
            reject(response, 413, "REQUEST_TOO_LARGE", correlation); return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            reject(response, 413, "REQUEST_TOO_LARGE", correlation); return;
        }
        ApiKeyParts apiKey = apiKey(request.getHeader("X-FlowDesk-Api-Key"));
        byte[] selectorHash = AlertIdempotencyCodec.sha256(apiKey.selector());
        List<WebhookIntegrationRepository.Candidate> candidates =
                integrations.findCandidates(selectorHash);
        byte[] signing = signingBytes(version, timestamp, nonce, body);
        byte[] supplied = HexFormat.of().parseHex(signature.substring("sha256=".length()));
        Instant now = clock.instant();
        List<WebhookIntegrationRepository.Candidate> passing = new ArrayList<>();
        if (candidates.isEmpty()) hmac(secrets.dummySecret(), signing);
        for (WebhookIntegrationRepository.Candidate candidate : candidates) {
            byte[] secret = secrets.secret(candidate.integrationId()).orElseGet(secrets::dummySecret);
            boolean pass = apiKey.valid()
                    & "ACTIVE".equals(candidate.status())
                    & "FD_API_KEY_SHA256_V1".equals(candidate.verifierScheme())
                    & candidate.signingVersion() == 1
                    & !now.isBefore(candidate.validFrom())
                    & (candidate.validUntil() == null || now.isBefore(candidate.validUntil()))
                    & MessageDigest.isEqual(AlertIdempotencyCodec.sha256(apiKey.verifier()),
                            candidate.apiKeyVerifier())
                    & MessageDigest.isEqual(hmac(secret, signing), supplied)
                    & secrets.secret(candidate.integrationId()).isPresent();
            if (pass) passing.add(candidate);
        }
        if (passing.size() != 1) {
            reject(response, 401, "WEBHOOK_AUTHENTICATION_FAILED", correlation); return;
        }
        final long epoch;
        try { epoch = Long.parseLong(timestamp); }
        catch (NumberFormatException invalid) {
            reject(response, 400, "WEBHOOK_TIMESTAMP_INVALID", correlation); return;
        }
        long delta;
        try { delta = Math.abs(Math.subtractExact(now.getEpochSecond(), epoch)); }
        catch (ArithmeticException overflow) { delta = Long.MAX_VALUE; }
        if (delta > properties.getTimestampWindow().toSeconds()) {
            reject(response, 401, "WEBHOOK_TIMESTAMP_EXPIRED", correlation); return;
        }
        WebhookIntegrationRepository.Candidate candidate = passing.get(0);
        request.setAttribute(WebhookRequestAttributes.VERIFIED, new VerifiedWebhook(
                candidate.tenantId(), candidate.integrationId(), candidate.integrationCode(),
                candidate.integrationName(), timestamp, Instant.ofEpochSecond(epoch), nonce,
                correlation, body, AlertIdempotencyCodec.sha256(body)));
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }

    public static VerifiedWebhook verified(HttpServletRequest request) {
        Object value = request.getAttribute(WebhookRequestAttributes.VERIFIED);
        if (!(value instanceof VerifiedWebhook verified)) {
            throw new IllegalStateException("Webhook authentication boundary was bypassed");
        }
        return verified;
    }

    private static ApiKeyParts apiKey(String raw) {
        var matcher = API_KEY.matcher(raw == null ? "" : raw);
        if (!matcher.matches()) {
            byte[] dummy = AlertIdempotencyCodec.sha256(
                    (raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8));
            return new ApiKeyParts(false, java.util.Arrays.copyOf(dummy, 16), dummy);
        }
        try {
            byte[] selector = Base64.getUrlDecoder().decode(matcher.group(1) + "==");
            byte[] verifier = Base64.getUrlDecoder().decode(matcher.group(2) + "=");
            return new ApiKeyParts(selector.length == 16 && verifier.length == 32,
                    selector, verifier);
        }
        catch (IllegalArgumentException invalid) {
            return new ApiKeyParts(false, new byte[16], new byte[32]);
        }
    }

    private static byte[] signingBytes(String version, String timestamp, String nonce, byte[] body) {
        byte[] prefix = (version + "\n" + timestamp + "\n" + nonce + "\nPOST\n" + PATH + "\n")
                .getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream value = new ByteArrayOutputStream(prefix.length + body.length);
        value.writeBytes(prefix); value.writeBytes(body); return value.toByteArray();
    }
    private static byte[] hmac(byte[] secret, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256")); return mac.doFinal(value);
        }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean contentTypeAccepted(String raw) {
        try {
            MediaType type = MediaType.parseMediaType(raw == null ? "" : raw);
            return MediaType.APPLICATION_JSON.includes(type) && type.getCharset() != null
                    && StandardCharsets.UTF_8.equals(type.getCharset());
        }
        catch (RuntimeException invalid) { return false; }
    }
    private void reject(HttpServletResponse response, int status, String code, UUID correlation)
            throws IOException {
        ApiErrorWriter.write(response, status, code, "Webhook 请求未被接纳。", false,
                correlation, List.of(), json);
    }
    private static UUID correlation(HttpServletRequest request) {
        Object value = request.getAttribute(FlowDeskRequestAttributes.CORRELATION_ID);
        return value instanceof UUID uuid ? uuid : UUID.randomUUID();
    }
    private static List<String> values(Enumeration<String> source) {
        if (source == null) return List.of();
        List<String> result = new ArrayList<>(); while (source.hasMoreElements()) result.add(source.nextElement());
        return result;
    }
    private record ApiKeyParts(boolean valid, byte[] selector, byte[] verifier) { }
}
