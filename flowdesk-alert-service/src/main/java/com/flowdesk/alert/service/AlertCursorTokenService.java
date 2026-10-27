package com.flowdesk.alert.service;

import com.flowdesk.common.web.ApiException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 为告警、故障和恢复列表生成并校验分页游标。
 *
 * <p>游标记录上一页最后一条数据的排序值和 ID，并把租户、筛选条件、页大小和排序一起签名。
 * 客户端应原样回传；更换筛选或排序后应从第一页重新查询。
 * Base64 只是传输编码，内容并未加密；HMAC 用来发现篡改，不能代替接口权限校验。
 */
@Component
public final class AlertCursorTokenService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final byte[] key;

    /**
     * 从配置解码游标签名密钥并检查最小强度，用于签发和核对绑定租户及查询范围的分页令牌。
     */
    public AlertCursorTokenService(CursorProperties properties) {
        try {
            key = Base64.getDecoder().decode(properties.hmacKey());
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Alert cursor key is invalid", invalid);
        }
        if (key.length < 32) throw new IllegalStateException("Alert cursor key is not configured");
    }

    /** 将查询作用域和位置一起签名，避免合法的分页位置被挪用于另一条查询。 */
    public String encode(UUID tenant, String scope, int size, String sort, InstantCursor value) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("tenant", tenant.toString());
            map.put("scope", scope);
            map.put("pageSize", size);
            map.put("sort", sort);
            map.put("lastValue", value.value());
            map.put("lastId", value.id().toString());
            String body =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(JSON.writeValueAsBytes(map));
            return body + "." + mac(body);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to encode Alert cursor", failure);
        }
    }

    /** 无 token 表示第一页；存在时先验签，再核对本次查询作用域，任一不符均拒绝续查。 */
    public InstantCursor decode(String token, UUID tenant, String scope, int size, String sort) {
        if (token == null) return null;
        try {
            int dot = token.lastIndexOf('.');
            if (dot <= 0) throw invalid();
            String body = token.substring(0, dot), supplied = token.substring(dot + 1);
            if (!MessageDigest.isEqual(
                    mac(body).getBytes(StandardCharsets.US_ASCII),
                    supplied.getBytes(StandardCharsets.US_ASCII))) throw invalid();
            @SuppressWarnings("unchecked")
            Map<String, Object> map =
                    JSON.readValue(Base64.getUrlDecoder().decode(body), Map.class);
            // 签名正确仍须核对查询作用域，禁止跨租户或修改筛选条件后复用旧游标。
            if (!tenant.toString().equals(map.get("tenant"))
                    || !scope.equals(map.get("scope"))
                    || !Integer.valueOf(size).equals(map.get("pageSize"))
                    || !sort.equals(map.get("sort"))) throw invalid();
            return new InstantCursor(
                    String.valueOf(map.get("lastValue")),
                    UUID.fromString(String.valueOf(map.get("lastId"))));
        } catch (ApiException expected) {
            throw expected;
        } catch (Exception invalid) {
            throw invalid();
        }
    }

    /**
     * 使用已校验的配置密钥计算 HMAC-SHA256，保护游标正文的完整性与查询边界。
     */
    private String mac(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.US_ASCII)));
    }

    /**
     * 构造当前校验路径使用的统一参数或游标错误，保证错误响应遵循对外契约。
     */
    private static ApiException invalid() {
        return ApiException.badRequest("CURSOR_INVALID", "pageToken 无效或与当前 Alert 查询作用域不匹配。");
    }

    /** value 是排序字段的文本值；id 用于在多个记录时间相同时确定稳定的续查位置。 */
    public record InstantCursor(String value, UUID id) {}

    /**
     * 告警游标签名密钥的配置载体；密钥以 Base64 提供，读取后由游标服务检查强度并用于多实例互认。
     */
    @ConfigurationProperties(prefix = "flowdesk.security.cursor-token")
    public static class CursorProperties {
        private String hmacKey = "";

        /**
         * 返回分页游标的 Base64 HMAC 签名密钥，供业务校验或组件初始化读取。
         */
        public String hmacKey() {
            return hmacKey;
        }

        /**
         * 更新分页游标的 Base64 HMAC 签名密钥，供 Spring 配置绑定及组件初始化使用。
         */
        public void setHmacKey(String value) {
            hmacKey = value;
        }
    }
}
