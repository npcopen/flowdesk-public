package com.flowdesk.common.security;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PemKeyReaderTest {

    @Test
    void parsesPkcs8PrivateAndX509PublicPem() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String privatePem = pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
        String publicPem = pem("PUBLIC KEY", keyPair.getPublic().getEncoded());

        PrivateKey parsedPrivate = PemKeyReader.readPrivateKey(privatePem);
        PublicKey parsedPublic = PemKeyReader.readPublicKey(publicPem);

        assertThat(parsedPrivate).isNotNull();
        assertThat(parsedPublic).isNotNull();
    }

    @Test
    void parsesTraditionalPkcs1RsaPem() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        RSAPrivateCrtKey rsaPrivate = (RSAPrivateCrtKey) keyPair.getPrivate();
        RSAPublicKey rsaPublic = (RSAPublicKey) keyPair.getPublic();

        byte[] pkcs1Private = sequence(
                integer(BigInteger.ZERO),
                integer(rsaPrivate.getModulus()),
                integer(rsaPrivate.getPublicExponent()),
                integer(rsaPrivate.getPrivateExponent()),
                integer(rsaPrivate.getPrimeP()),
                integer(rsaPrivate.getPrimeQ()),
                integer(rsaPrivate.getPrimeExponentP()),
                integer(rsaPrivate.getPrimeExponentQ()),
                integer(rsaPrivate.getCrtCoefficient()));
        byte[] pkcs1Public = sequence(
                integer(rsaPublic.getModulus()),
                integer(rsaPublic.getPublicExponent()));

        assertThat(PemKeyReader.readPrivateKey(pem("RSA PRIVATE KEY", pkcs1Private))).isNotNull();
        assertThat(PemKeyReader.readPublicKey(pem("RSA PUBLIC KEY", pkcs1Public))).isNotNull();
    }

    @Test
    void rejectsNonPemText() {
        assertThatThrownBy(() -> PemKeyReader.readPublicKey("not a pem"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String pem(String label, byte[] der) {
        String nl = System.lineSeparator();
        String base64 = Base64.getMimeEncoder(64, nl.getBytes(StandardCharsets.UTF_8))
                .encodeToString(der);
        return "-----BEGIN " + label + "-----" + nl + base64 + nl + "-----END " + label + "-----" + nl;
    }

    private static byte[] sequence(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return tlv(0x30, out.toByteArray());
    }

    private static byte[] integer(BigInteger value) {
        return tlv(0x02, value.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeLength(out, content.length);
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length);
            return;
        }
        int needed = 0;
        int remaining = length;
        while (remaining > 0) {
            needed++;
            remaining >>>= 8;
        }
        out.write(0x80 | needed);
        for (int i = needed - 1; i >= 0; i--) {
            out.write((length >>> (8 * i)) & 0xff);
        }
    }
}
