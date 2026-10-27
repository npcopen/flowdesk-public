package com.flowdesk.common.security;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读取 FlowDesk 使用的 RSA PEM 密钥。
 * 支持 PKCS#8 私钥、PKCS#1 RSA 私钥、X.509 公钥和 PKCS#1 RSA 公钥；不记录私钥文本。
 */
public final class PemKeyReader {

    private static final Pattern PEM_BLOCK =
            Pattern.compile("-----BEGIN ([A-Z0-9 ]+)-----\\s*(.*?)\\s*-----END \\1-----", Pattern.DOTALL);

    private static final byte[] RSA_OID = {0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01};

    /**
     * 禁止直接实例化该工具类型，统一通过静态方法或工厂入口使用。
     */
    private PemKeyReader() {
    }

    /**
     * 识别 PKCS#8 或传统 PKCS#1 RSA 私钥，必要时补充 PKCS#8 包装后交由 RSA KeyFactory 解析。
     */
    public static PrivateKey readPrivateKey(String pem) {
        byte[] der = decodeDer(pem);
        String blockLabel = label(pem);
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            byte[] pkcs8 = switch (blockLabel) {
                case "PRIVATE KEY" -> der;
                case "RSA PRIVATE KEY" -> wrapRsaPrivateKeyInPkcs8(der);
                default -> throw new IllegalArgumentException("Unsupported private key PEM block: " + blockLabel);
            };
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to parse RSA private key", e);
        }
    }

    /**
     * 识别 X.509 或传统 PKCS#1 RSA 公钥，必要时补充 SubjectPublicKeyInfo 结构后解析。
     */
    public static PublicKey readPublicKey(String pem) {
        byte[] der = decodeDer(pem);
        String blockLabel = label(pem);
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            byte[] x509 = switch (blockLabel) {
                case "PUBLIC KEY" -> der;
                case "RSA PUBLIC KEY" -> wrapRsaPublicKeyInSubjectPublicKeyInfo(der);
                default -> throw new IllegalArgumentException("Unsupported public key PEM block: " + blockLabel);
            };
            return keyFactory.generatePublic(new X509EncodedKeySpec(x509));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to parse RSA public key", e);
        }
    }

    /**
     * 提取 PEM 边界标签并规范化为大写，未找到合法块时拒绝输入。
     */
    public static String label(String pem) {
        Matcher matcher = PEM_BLOCK.matcher(pem);
        if (!matcher.find()) {
            throw new IllegalArgumentException("PEM block not found");
        }
        return matcher.group(1).toUpperCase(Locale.ROOT);
    }

    /**
     * 提取 PEM 的 Base64 内容、去除空白并解码为 DER 字节。
     */
    private static byte[] decodeDer(String pem) {
        Matcher matcher = PEM_BLOCK.matcher(pem);
        if (!matcher.find()) {
            throw new IllegalArgumentException("PEM block not found");
        }
        String base64 = matcher.group(2).replaceAll("\\s+", "");
        return Base64.getMimeDecoder().decode(base64);
    }

    /**
     * 为 PKCS#1 私钥添加版本和 RSA 算法标识，形成标准 PKCS#8 PrivateKeyInfo。
     */
    private static byte[] wrapRsaPrivateKeyInPkcs8(byte[] rsaPrivateKeyDer) {
        return derSequence(
                derInteger(BigInteger.ZERO),
                derSequence(derOid(RSA_OID), derNull()),
                derOctetString(rsaPrivateKeyDer));
    }

    /**
     * 为 PKCS#1 公钥补充 RSA 算法标识和 BIT STRING，形成 X.509 公钥结构。
     */
    private static byte[] wrapRsaPublicKeyInSubjectPublicKeyInfo(byte[] rsaPublicKeyDer) {
        return derSequence(
                derSequence(derOid(RSA_OID), derNull()),
                derBitString(rsaPublicKeyDer));
    }

    /** 按确定长度编码 DER，不依赖标记扫描或事后修补长度。 */
    private static byte[] derSequence(byte[]... contents) {
        return der((byte) 0x30, concat(contents));
    }

    /**
     * 用 ASN.1 INTEGER 标签包装整数的有符号字节表示。
     */
    private static byte[] derInteger(BigInteger value) {
        return der((byte) 0x02, value.toByteArray());
    }

    /**
     * 用 OBJECT IDENTIFIER 标签包装已经编码的算法 OID。
     */
    private static byte[] derOid(byte[] oid) {
        return der((byte) 0x06, oid);
    }

    /**
     * 生成 RSA 算法参数所需的 ASN.1 NULL 编码。
     */
    private static byte[] derNull() {
        return new byte[]{0x05, 0x00};
    }

    /**
     * 将 DER 字节包装为 OCTET STRING，供 PKCS#8 私钥结构使用。
     */
    private static byte[] derOctetString(byte[] bytes) {
        return der((byte) 0x04, bytes);
    }

    /**
     * 在公钥内容前写入零个未使用位，再生成 BIT STRING 编码。
     */
    private static byte[] derBitString(byte[] bytes) {
        byte[] content = new byte[bytes.length + 1];
        content[0] = 0x00; // zero unused bits
        System.arraycopy(bytes, 0, content, 1, bytes.length);
        return der((byte) 0x03, content);
    }

    /**
     * 依次写入 ASN.1 标签、DER 长度与内容，构造单个元素。
     */
    private static byte[] der(byte tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 8);
        out.write(tag);
        writeDerLength(out, content.length);
        out.writeBytes(content);
        return out.toByteArray();
    }

    /**
     * 按 DER 规则写入短长度或长长度，支持超过 127 字节的密钥内容。
     */
    private static void writeDerLength(ByteArrayOutputStream out, int length) {
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

    /**
     * 按顺序拼接子元素字节数组，供 ASN.1 SEQUENCE 构造使用。
     */
    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] array : arrays) {
            total += array.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

}
