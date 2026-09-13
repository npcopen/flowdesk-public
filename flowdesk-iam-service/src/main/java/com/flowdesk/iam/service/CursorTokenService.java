package com.flowdesk.iam.service;

import tools.jackson.databind.ObjectMapper;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.iam.security.CursorTokenProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Opaque, HMAC-integrity-protected page tokens bound to tenant, scope, sort and page
 * size. The HMAC key is injected from configuration so multiple IAM instances and
 * restarts can share the same cursors.
 */
@Component
public class CursorTokenService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final byte[] key;

    public CursorTokenService(CursorTokenProperties properties) {
        this.key = decodeKey(properties.hmacKey());
    }

    private static byte[] decodeKey(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException(
                    "Cursor token HMAC key is not configured (flowdesk.security.cursor-token.hmac-key)");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Cursor token HMAC key must be base64-encoded", e);
        }
        if (decoded.length < 32) {
            throw new IllegalStateException(
                    "Cursor token HMAC key must be at least 32 bytes after base64 decoding");
        }
        return decoded;
    }

    public String encode(UUID tenantId, String scope, int pageSize, String sort,
                         String lastName, UUID lastId) {
        try {
            String payload = MAPPER.writeValueAsString(Map.of(
                    "tenant", tenantId.toString(),
                    "scope", scope,
                    "pageSize", pageSize,
                    "sort", sort,
                    "lastName", lastName,
                    "lastId", lastId.toString()));
            String token = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            return token + "." + hmac(token);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encode page token", e);
        }
    }

    /**
     * Decodes a page token, or returns {@code null} when no token was supplied.
     *
     * <p>API-SPEC §2.7 requires tampered, malformed, expired or cross-scope tokens to
     * fail with 400 CURSOR_INVALID instead of silently restarting from the first page.
     * Only a missing token (first page request) is represented by {@code null}.</p>
     */
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
            return new Cursor(String.valueOf(map.get("lastName")),
                    UUID.fromString(String.valueOf(map.get("lastId"))));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw invalidCursor();
        }
    }

    private String hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign page token", e);
        }
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        return java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static ApiException invalidCursor() {
        return ApiException.badRequest("CURSOR_INVALID", "pageToken 无效、过期或与当前作用域不匹配。");
    }

    public record Cursor(String lastName, UUID lastId) {
    }
}
