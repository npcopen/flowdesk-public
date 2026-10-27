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

/** 签发并验证绑定租户、查询作用域、分页大小和排序的通知列表游标。 */
@Service
@EnableConfigurationProperties(NotificationCursorTokenService.CursorProperties.class)
public class NotificationCursorTokenService {
    private final CursorProperties properties;

    /**
     * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
     */
    public NotificationCursorTokenService(CursorProperties properties) {
        this.properties = properties;
    }

    /**
     * 将租户、查询范围、分页大小及最后记录边界编码为签名游标，阻止客户端扩大范围。
     */
    public String encode(UUID tenantId, String scope, int size, String sort,
                         Instant value, UUID id) {
        String payload = tenantId + "|" + scope + "|" + size + "|" + sort + "|"
                + value + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (payload + "|" + sign(payload)).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证游标签名及绑定条件，再返回分页边界；过期、不匹配或篡改的输入被拒绝。
     */
    public Cursor decode(String token, UUID tenantId, String scope, int size, String sort) {
        if (token == null || token.isBlank()) return null;
        try {
            String value = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", -1);
            if (parts.length < 7) throw new IllegalArgumentException();
            // Scope 自身包含分隔符；从固定尾部定位分页字段，保留原 token 的签名与编码格式。
            int sizeIndex = parts.length - 5;
            String encodedScope = String.join("|", java.util.Arrays.copyOfRange(parts, 1, sizeIndex));
            String payload = String.join("|", java.util.Arrays.copyOf(parts, parts.length - 1));
            // 核验签名后仍检查租户、筛选作用域、分页大小和排序，防止跨查询复用游标。
            if (!constantTime(sign(payload), parts[parts.length - 1])
                    || !tenantId.toString().equals(parts[0])
                    || !scope.equals(encodedScope) || Integer.parseInt(parts[sizeIndex]) != size
                    || !sort.equals(parts[sizeIndex + 1])) throw new IllegalArgumentException();
            return new Cursor(Instant.parse(parts[sizeIndex + 2]), UUID.fromString(parts[sizeIndex + 3]));
        }
        catch (Exception invalid) {
            throw ApiException.badRequest("PAGE_TOKEN_INVALID", "分页 token 无效或不属于当前查询。");
        }
    }

    /**
     * 使用配置密钥签名游标内容，防止客户端修改分页边界或查询范围。
     */
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

    /**
     * 以恒定时间比较方式核验签名，避免普通比较提前退出带来的差异。
     */
    private static boolean constantTime(String left, String right) {
        return java.security.MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签名游标解码后的分页边界，限定租户、筛选范围、排序及末条记录。
     */
    public record Cursor(Instant value, UUID id) {
    }

    /**
     * 游标签名密钥配置，供编码和验签共用服务端密钥。
     */
    @ConfigurationProperties(prefix = "flowdesk.notification.cursor-token")
    public static class CursorProperties {
        private String hmacKey = "";
        // 兼容旧调用方使用通用 key 名称的访问器。
        /**
         * 读取游标配置的密钥值，供编码和验签使用一致的签名材料。
         */
        public String key() { return hmacKey; }
        /**
         * 读取 HMAC 配置密钥，供游标签名与验证共用。
         */
        public String hmacKey() { return hmacKey; }
        /**
         * 设置游标签名密钥，供配置绑定和组件初始化使用。
         */
        public void setHmacKey(String hmacKey) { this.hmacKey = hmacKey; }
        /**
         * 设置游标签名密钥，供配置绑定和组件初始化使用。
         */
        public void setKey(String key) { this.hmacKey = key; }
    }
}
