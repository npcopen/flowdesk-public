package com.flowdesk.notification.service;

import com.flowdesk.common.web.ApiException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Opaque, tenant- and query-bound cursor for Notification collections. */
@Service
@EnableConfigurationProperties(NotificationCursorTokenService.CursorProperties.class)
public class NotificationCursorTokenService {
    private final CursorProperties properties;

    public NotificationCursorTokenService(CursorProperties properties) {
        this.properties = properties;
    }

    public String encode(UUID tenantId, String scope, int size, String sort,
                         Instant value, UUID id) {
        String payload = tenantId + "|" + scope + "|" + size + "|" + sort + "|"
                + value + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (payload + "|" + sign(payload)).getBytes(StandardCharsets.UTF_8));
    }

    public Cursor decode(String token, UUID tenantId, String scope, int size, String sort) {
        if (token == null || token.isBlank()) return null;
        try {
            String value = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", -1);
            if (parts.length != 7) throw new IllegalArgumentException();
            String payload = String.join("|", java.util.Arrays.copyOf(parts, 6));
            if (!constantTime(sign(payload), parts[6])
                    || !tenantId.toString().equals(parts[0])
                    || !scope.equals(parts[1]) || Integer.parseInt(parts[2]) != size
                    || !sort.equals(parts[3])) throw new IllegalArgumentException();
            return new Cursor(Instant.parse(parts[4]), UUID.fromString(parts[5]));
        }
        catch (Exception invalid) {
            throw ApiException.badRequest("PAGE_TOKEN_INVALID", "分页 token 无效或不属于当前查询。");
        }
    }

    private String sign(String value) {
        try {
            if (properties.key() == null || properties.key().length() < 32) {
                throw new IllegalStateException("Notification cursor-token HMAC key must contain at least 32 characters");
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.key().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception e) { throw new IllegalStateException("Cursor signing unavailable", e); }
    }

    private static boolean constantTime(String left, String right) {
        return java.security.MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    public record Cursor(Instant value, UUID id) {
    }

    @ConfigurationProperties(prefix = "flowdesk.notification.cursor-token")
    public static class CursorProperties {
        private String hmacKey = "";
        /** Compatibility accessor for older callers that used a generic key name. */
        public String key() { return hmacKey; }
        public String hmacKey() { return hmacKey; }
        public void setHmacKey(String hmacKey) { this.hmacKey = hmacKey; }
        public void setKey(String key) { this.hmacKey = key; }
    }
}
