package com.flowdesk.ticket.service;

import com.flowdesk.common.web.ApiException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * 以 HMAC 保护分页令牌，并绑定租户、查询范围、排序和分页大小。
 * 游标值按 UUID、Instant 或可排序文本的规范形式编码，由调用方按排序契约还原。
 */
@Component
public class TicketCursorTokenService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final byte[] key;

    /**
     * 读取并解码配置的 HMAC 密钥；缺失、编码非法或解码后不足 32 字节时拒绝初始化。
     */
    public TicketCursorTokenService(CursorProperties properties) {
        this.key = decodeKey(properties.hmacKey());
    }

    /**
     * 解码并校验游标签名密钥，拒绝使用无效密钥构造服务。
     */
    private static byte[] decodeKey(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException(
                    "Ticket cursor token HMAC key is not configured (flowdesk.security.cursor-token.hmac-key)");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Ticket cursor token HMAC key must be base64-encoded", e);
        }
        if (decoded.length < 32) {
            throw new IllegalStateException(
                    "Ticket cursor token HMAC key must be at least 32 bytes after base64 decoding");
        }
        return decoded;
    }

    /** 签名分页上下文和最后一条记录的排序键，令牌可传回但不能被客户端任意改写。 */
    public String encode(UUID tenantId, String scope, int pageSize, String sort,
                         String lastValue, UUID lastId) {
        try {
            String payload = MAPPER.writeValueAsString(Map.of(
                    "tenant", tenantId.toString(),
                    "scope", scope,
                    "pageSize", pageSize,
                    "sort", sort,
                    "lastValue", lastValue,
                    "lastId", lastId.toString()));
            String token = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            return token + "." + hmac(token);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encode ticket page token", e);
        }
    }

    /** 先验证 HMAC，再核对租户、查询范围、排序和页大小；任一不匹配均拒绝继续翻页。 */
    public Cursor decode(String token, UUID tenantId, String scope, int pageSize, String sort) {
        if (token == null) {
            return null;
        }
        try {
            int dot = token.lastIndexOf('.');
            if (dot <= 0 || dot == token.length() - 1) {
                throw invalidCursor();
            }
            String body = token.substring(0, dot);
            String suppliedMac = token.substring(dot + 1);
            if (!constantTimeEquals(hmac(body), suppliedMac)) {
                throw invalidCursor();
            }
            byte[] json = Base64.getUrlDecoder().decode(body);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = MAPPER.readValue(json, Map.class);
            if (!tenantId.toString().equals(map.get("tenant"))
                    || !scope.equals(map.get("scope"))
                    || !Integer.valueOf(pageSize).equals(map.get("pageSize"))
                    || !sort.equals(map.get("sort"))) {
                throw invalidCursor();
            }
            return new Cursor(String.valueOf(map.get("lastValue")),
                    UUID.fromString(String.valueOf(map.get("lastId"))));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw invalidCursor();
        }
    }

    /**
     * 以配置密钥计算消息认证码，防止客户端篡改分页游标。
     */
    private String hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign ticket page token", e);
        }
    }

    /**
     * 以固定时间比较签名或摘要，避免泄露逐字符匹配位置。
     */
    private static boolean constantTimeEquals(String expected, String supplied) {
        return java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 构造统一游标错误，避免暴露内部签名与解析细节。
     */
    private static ApiException invalidCursor() {
        return ApiException.badRequest("CURSOR_INVALID", "pageToken 无效、过期或与当前作用域不匹配。");
    }

    /**
     * 游标签名配置，提供可绑定的 HMAC 密钥。
     */
    @ConfigurationProperties(prefix = "flowdesk.security.cursor-token")
    public static class CursorProperties {
        private String hmacKey = "";

        /**
         * 返回当前游标签名密钥，供请求处理或配置读取。
         */
        public String hmacKey() {
            return hmacKey;
        }

        /**
         * 设置游标签名密钥，供配置绑定与启动校验使用。
         */
        public void setHmacKey(String hmacKey) {
            this.hmacKey = hmacKey;
        }
    }

    /**
     * 已验证分页边界，绑定租户、筛选范围、排序及最后记录位置。
     */
    public record Cursor(String lastValue, UUID lastId) {
    }
}
