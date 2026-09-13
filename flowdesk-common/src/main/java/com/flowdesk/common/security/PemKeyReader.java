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
 * Minimal PEM reader for the RSA key formats used by FlowDesk.
 *
 * <p>Supports PKCS#8 {@code PRIVATE KEY}, traditional PKCS#1 {@code RSA PRIVATE KEY},
 * X.509 {@code PUBLIC KEY} and PKCS#1 {@code RSA PUBLIC KEY}. The key material is
 * parsed from PEM text; private key text is never logged.</p>
 */
public final class PemKeyReader {

    private static final Pattern PEM_BLOCK =
            Pattern.compile("-----BEGIN ([A-Z0-9 ]+)-----\\s*(.*?)\\s*-----END \\1-----", Pattern.DOTALL);

    private static final byte[] RSA_OID = {0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01};

    private PemKeyReader() {
    }

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

    public static String label(String pem) {
        Matcher matcher = PEM_BLOCK.matcher(pem);
        if (!matcher.find()) {
            throw new IllegalArgumentException("PEM block not found");
        }
        return matcher.group(1).toUpperCase(Locale.ROOT);
    }

    private static byte[] decodeDer(String pem) {
        Matcher matcher = PEM_BLOCK.matcher(pem);
        if (!matcher.find()) {
            throw new IllegalArgumentException("PEM block not found");
        }
        String base64 = matcher.group(2).replaceAll("\\s+", "");
        return Base64.getMimeDecoder().decode(base64);
    }

    private static byte[] wrapRsaPrivateKeyInPkcs8(byte[] rsaPrivateKeyDer) {
        return derSequence(
                derInteger(BigInteger.ZERO),
                derSequence(derOid(RSA_OID), derNull()),
                derOctetString(rsaPrivateKeyDer));
    }

    private static byte[] wrapRsaPublicKeyInSubjectPublicKeyInfo(byte[] rsaPublicKeyDer) {
        return derSequence(
                derSequence(derOid(RSA_OID), derNull()),
                derBitString(rsaPublicKeyDer));
    }

    /** Deterministic definite-length DER writer; no marker scanning or length patching. */
    private static byte[] derSequence(byte[]... contents) {
        return der((byte) 0x30, concat(contents));
    }

    private static byte[] derInteger(BigInteger value) {
        return der((byte) 0x02, value.toByteArray());
    }

    private static byte[] derOid(byte[] oid) {
        return der((byte) 0x06, oid);
    }

    private static byte[] derNull() {
        return new byte[]{0x05, 0x00};
    }

    private static byte[] derOctetString(byte[] bytes) {
        return der((byte) 0x04, bytes);
    }

    private static byte[] derBitString(byte[] bytes) {
        byte[] content = new byte[bytes.length + 1];
        content[0] = 0x00; // zero unused bits
        System.arraycopy(bytes, 0, content, 1, bytes.length);
        return der((byte) 0x03, content);
    }

    private static byte[] der(byte tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 8);
        out.write(tag);
        writeDerLength(out, content.length);
        out.writeBytes(content);
        return out.toByteArray();
    }

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
