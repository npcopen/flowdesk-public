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
 * 签发和验证 HMAC 保护的不透明分页游标，将租户、查询范围、排序及页大小绑定到同一游标。
 * 签名密钥来自配置，以支持多实例互认及服务重启后的继续翻页。
 */
@Component
public class CursorTokenService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final byte[] key;

    /**
     * 读取并校验配置中的 Base64 HMAC 密钥，后续签名和验签共享同一稳定密钥。
     */
    public CursorTokenService(CursorTokenProperties properties) {
        this.key = decodeKey(properties.hmacKey());
    }

    /**
     * 解码并校验 Base64 游标签名密钥，缺失、格式非法或不足 32 字节时启动失败。
     */
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

    /**
     * 将租户、查询作用域、页大小、排序及末条位置序列化，并附上 HMAC 签名形成不透明续查令牌。
     */
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
     * 仅首次请求未提供令牌时返回 {@code null}。
     * 令牌被篡改、格式异常或与当前范围不匹配时返回 400 CURSOR_INVALID，不能静默退回第一页。
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

    /**
     * 以配置密钥对游标正文计算 HMAC-SHA256，并使用小写十六进制表示签名。
     */
    private String hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign page token", e);
        }
    }

    /**
     * 使用恒定时间字节比较核对签名，减少普通字符串提前返回带来的时序差异。
     */
    private static boolean constantTimeEquals(String expected, String supplied) {
        return java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 构造统一的 CURSOR_INVALID 参数错误，避免无效游标静默退回第一页。
     */
    private static ApiException invalidCursor() {
        return ApiException.badRequest("CURSOR_INVALID", "pageToken 无效、过期或与当前作用域不匹配。");
    }

    /**
     * 已验签并核对查询作用域后的分页位置，保留上一页排序值和资源标识以稳定续查。
     */
    public record Cursor(String lastName, UUID lastId) {
    }
}
