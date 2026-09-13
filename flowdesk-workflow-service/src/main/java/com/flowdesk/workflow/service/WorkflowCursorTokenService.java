package com.flowdesk.workflow.service;

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

/** Opaque HMAC cursor tokens bound to tenant, query scope, sort and page size. */
@Component
public class WorkflowCursorTokenService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final String encodedKey;

    public WorkflowCursorTokenService(CursorProperties properties) {
        this.encodedKey = properties.hmacKey();
    }

    public String encode(UUID tenantId, String scope, int pageSize, String sort,
                         String lastValue, UUID lastId) {
        try {
            String payload = JSON.writeValueAsString(Map.of(
                    "tenant", tenantId.toString(), "scope", scope,
                    "pageSize", pageSize, "sort", sort,
                    "lastValue", lastValue, "lastId", lastId.toString()));
            String body = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            return body + "." + hmac(body);
        }
        catch (ApiException e) {
            throw e;
        }
        catch (Exception e) {
            throw new IllegalStateException("Unable to encode Workflow cursor", e);
        }
    }

    public Cursor decode(String token, UUID tenantId, String scope, int pageSize, String sort) {
        if (token == null) {
            return null;
        }
        try {
            int dot = token.lastIndexOf('.');
            if (dot <= 0 || dot == token.length() - 1) {
                throw invalid();
            }
            String body = token.substring(0, dot);
            String signature = token.substring(dot + 1);
            if (!java.security.MessageDigest.isEqual(
                    hmac(body).getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8))) {
                throw invalid();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = JSON.readValue(
                    Base64.getUrlDecoder().decode(body), Map.class);
            if (!tenantId.toString().equals(fields.get("tenant"))
                    || !scope.equals(fields.get("scope"))
                    || !Integer.valueOf(pageSize).equals(fields.get("pageSize"))
                    || !sort.equals(fields.get("sort"))) {
                throw invalid();
            }
            return new Cursor(String.valueOf(fields.get("lastValue")),
                    UUID.fromString(String.valueOf(fields.get("lastId"))));
        }
        catch (ApiException e) {
            throw e;
        }
        catch (Exception e) {
            throw invalid();
        }
    }

    private String hmac(String body) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encodedKey == null ? "" : encodedKey.trim());
        }
        catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Workflow cursor HMAC key must be base64", invalid);
        }
        if (key.length < 32) {
            throw new IllegalStateException(
                    "Workflow cursor HMAC key must contain at least 32 decoded bytes");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception e) {
            throw new IllegalStateException("Unable to sign Workflow cursor", e);
        }
    }

    private static ApiException invalid() {
        return ApiException.badRequest("CURSOR_INVALID",
                "pageToken 无效、过期或与当前作用域不匹配。");
    }

    @ConfigurationProperties(prefix = "flowdesk.security.cursor-token")
    public static class CursorProperties {
        private String hmacKey = "";

        public String hmacKey() {
            return hmacKey;
        }

        public void setHmacKey(String hmacKey) {
            this.hmacKey = hmacKey;
        }
    }

    public record Cursor(String lastValue, UUID lastId) {
    }
}
