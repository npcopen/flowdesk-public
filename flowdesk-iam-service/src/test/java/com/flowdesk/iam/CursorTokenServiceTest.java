package com.flowdesk.iam;

import com.flowdesk.iam.security.CursorTokenProperties;
import com.flowdesk.iam.service.CursorTokenService;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.flowdesk.common.web.ApiException;

class CursorTokenServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LAST_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

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

    @Test
    void differentConfiguredKeysRejectEachOthersTokens() {
        CursorTokenService first = service((byte) 0x11);
        CursorTokenService second = service((byte) 0x22);

        String token = first.encode(TENANT, "scope", 20, "code", "CODE-1", LAST_ID);

        assertThatThrownBy(() -> second.decode(token, TENANT, "scope", 20, "code"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("pageToken");
    }

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

    private static CursorTokenService service(byte fill) {
        CursorTokenProperties properties = new CursorTokenProperties();
        properties.setHmacKey(Base64.getEncoder().encodeToString(key(fill)));
        return new CursorTokenService(properties);
    }

    private static byte[] key(byte fill) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, fill);
        return key;
    }
}
