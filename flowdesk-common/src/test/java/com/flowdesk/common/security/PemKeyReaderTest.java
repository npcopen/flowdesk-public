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

/**
 * 验证标准及传统 RSA PEM 密钥格式解析，并以最小 DER 编码辅助构造兼容性输入。
 */
class PemKeyReaderTest {

    /**
     * 验证可读取 PKCS#8 私钥及 X.509 公钥 PEM，兼容标准 Java RSA 密钥导出格式。
     */
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

    /**
     * 验证传统 PKCS#1 RSA 公私钥 PEM 能被正确转换并读取。
     */
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

    /**
     * 验证非 PEM 文本明确报错，不能被当作可用验签材料。
     */
    @Test
    void rejectsNonPemText() {
        assertThatThrownBy(() -> PemKeyReader.readPublicKey("not a pem"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 将给定 DER 字节按指定标签和 64 字符换行编码为 PEM，供密钥格式测试生成输入。
     */
    private static String pem(String label, byte[] der) {
        String nl = System.lineSeparator();
        String base64 = Base64.getMimeEncoder(64, nl.getBytes(StandardCharsets.UTF_8))
                .encodeToString(der);
        return "-----BEGIN " + label + "-----" + nl + base64 + nl + "-----END " + label + "-----" + nl;
    }

    /**
     * 依次组合 ASN.1 元素并包装为 DER SEQUENCE，构造传统 RSA 密钥结构。
     */
    private static byte[] sequence(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return tlv(0x30, out.toByteArray());
    }

    /**
     * 把大整数编码为 DER INTEGER，保留 BigInteger 的符号字节语义。
     */
    private static byte[] integer(BigInteger value) {
        return tlv(0x02, value.toByteArray());
    }

    /**
     * 按指定标签、内容长度及字节构造 DER TLV 元素，供 PKCS#1 测试数据组合。
     */
    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeLength(out, content.length);
        out.writeBytes(content);
        return out.toByteArray();
    }

    /**
     * 按 DER 短长度或长长度规则写入内容字节数，覆盖长度超过 127 的 RSA 密钥元素。
     */
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
