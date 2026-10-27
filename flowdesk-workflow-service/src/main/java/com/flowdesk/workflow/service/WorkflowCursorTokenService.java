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

/**
 * 签发和验证 HMAC 分页游标，将租户、查询范围、排序方式和页大小绑定在一起。
 */
@Component
public class WorkflowCursorTokenService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final String encodedKey;

    /**
     * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
     */
    public WorkflowCursorTokenService(CursorProperties properties) {
        this.encodedKey = properties.hmacKey();
    }

    /**
     * 将租户、查询范围、分页大小及最后记录边界编码为签名游标，阻止客户端扩大范围。
     */
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

    /**
     * 验证游标签名及绑定条件，再返回分页边界；过期、不匹配或篡改的输入被拒绝。
     */
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

    /**
     * 使用服务端密钥计算认证码，供分页游标防篡改校验。
     */
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

    /**
     * 构造统一的非法游标错误，避免向客户端暴露具体签名或内部编码细节。
     */
    private static ApiException invalid() {
        return ApiException.badRequest("CURSOR_INVALID",
                "pageToken 无效、过期或与当前作用域不匹配。");
    }

    /**
     * 游标签名密钥配置，供编码和验签共用服务端密钥。
     */
    @ConfigurationProperties(prefix = "flowdesk.security.cursor-token")
    public static class CursorProperties {
        private String hmacKey = "";

        /**
         * 读取 HMAC 配置密钥，供游标签名与验证共用。
         */
        public String hmacKey() {
            return hmacKey;
        }

        /**
         * 设置游标签名密钥，供配置绑定和组件初始化使用。
         */
        public void setHmacKey(String hmacKey) {
            this.hmacKey = hmacKey;
        }
    }

    /**
     * 签名游标解码后的分页边界，限定租户、筛选范围、排序及末条记录。
     */
    public record Cursor(String lastValue, UUID lastId) {
    }
}
