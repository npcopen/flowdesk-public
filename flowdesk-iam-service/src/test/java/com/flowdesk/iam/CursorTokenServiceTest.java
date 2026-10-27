package com.flowdesk.iam;

import com.flowdesk.iam.security.CursorTokenProperties;
import com.flowdesk.iam.service.CursorTokenService;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.flowdesk.common.web.ApiException;

/**
 * 验证IAM 分页游标服务的行为约束；结合可控测试数据检查正常流程、失败边界或状态收敛，避免回归破坏既有契约。
 */
class CursorTokenServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LAST_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    /**
     * 验证使用同一配置密钥的不同游标服务实例可互相验签，保证多实例部署及重启后的继续翻页。
     */
    @Test
    void sameConfiguredKeyDecodesAcrossInstances() {
        CursorTokenService first = service((byte) 0x11);
        CursorTokenService second = service((byte) 0x11);

        String token = first.encode(TENANT, "scope", 20, "code", "CODE-1", LAST_ID);
        CursorTokenService.Cursor decoded = second.decode(token, TENANT, "scope", 20, "code");

        assertThat(decoded).isNotNull();
        assertThat(decoded.lastName()).isEqualTo("CODE-1");
        assertThat(decoded.lastId()).isEqualTo(LAST_ID);
    }

    /**
     * 验证不同密钥签发的游标不能互认，确保未持有签名密钥的实例无法伪造分页位置。
     */
    @Test
    void differentConfiguredKeysRejectEachOthersTokens() {
        CursorTokenService first = service((byte) 0x11);
        CursorTokenService second = service((byte) 0x22);

        String token = first.encode(TENANT, "scope", 20, "code", "CODE-1", LAST_ID);

        assertThatThrownBy(() -> second.decode(token, TENANT, "scope", 20, "code"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("pageToken");
    }

    /**
     * 验证无令牌表示第一页，而篡改或跨作用域令牌明确失败，避免无效游标静默扩大查询范围。
     */
    @Test
    void missingTokenMeansFirstPageButTamperedOrCrossScopeTokenFailsClosed() {
        CursorTokenService service = service((byte) 0x11);

        assertThat(service.decode(null, TENANT, "scope", 20, "code")).isNull();

        String token = service.encode(TENANT, "scope", 20, "code", "CODE-1", LAST_ID);
        String tampered = token.substring(0, token.length() - 2) + "00";
        assertThatThrownBy(() -> service.decode(tampered, TENANT, "scope", 20, "code"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("pageToken");

        assertThatThrownBy(() -> service.decode(token, UUID.randomUUID(), "scope", 20, "code"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("pageToken");
    }

    /**
     * 验证缺失或过短的游标签名密钥会使组件立即初始化失败。
     */
    @Test
    void missingOrShortKeyFailsFast() {
        CursorTokenProperties missing = new CursorTokenProperties();
        assertThatThrownBy(() -> new CursorTokenService(missing))
                .isInstanceOf(IllegalStateException.class);

        CursorTokenProperties shortKey = new CursorTokenProperties();
        shortKey.setHmacKey(Base64.getEncoder().encodeToString(new byte[16]));
        assertThatThrownBy(() -> new CursorTokenService(shortKey))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 使用给定签名配置构造游标服务实例，验证跨实例互认或密钥隔离。
     */
    private static CursorTokenService service(byte fill) {
        CursorTokenProperties properties = new CursorTokenProperties();
        properties.setHmacKey(Base64.getEncoder().encodeToString(key(fill)));
        return new CursorTokenService(properties);
    }

    /**
     * 生成符合当前测试用途的固定密钥字节，使签名或摘要断言可重复。
     */
    private static byte[] key(byte fill) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, fill);
        return key;
    }
}
