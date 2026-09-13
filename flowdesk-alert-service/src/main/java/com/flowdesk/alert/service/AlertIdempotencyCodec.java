package com.flowdesk.alert.service;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

/** M5-compatible length-framed identities, kept local to the Alert service. */
public final class AlertIdempotencyCodec {
    private AlertIdempotencyCodec() { }

    public static byte[] derivedKey(String namespace, Object... values) {
        Object[] fields = new Object[values.length + 1];
        fields[0] = namespace;
        System.arraycopy(values, 0, fields, 1, values.length);
        return sha256(frame(fields));
    }

    public static byte[] publicationKey(UUID tenantId, UUID messageId) {
        return derivedKey("FD-PUB-V1", tenantId, messageId);
    }

    public static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }

    public static UUID uuidFromDigest(byte[] digest) {
        ByteBuffer value = ByteBuffer.wrap(Arrays.copyOf(digest, 16));
        return new UUID(value.getLong(), value.getLong());
    }

    private static byte[] frame(Object... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object field : fields) {
            byte[] bytes;
            if (field instanceof UUID uuid) {
                bytes = ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits())
                        .putLong(uuid.getLeastSignificantBits()).array();
            }
            else if (field instanceof byte[] raw) bytes = raw;
            else bytes = field.toString().getBytes(StandardCharsets.UTF_8);
            out.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
            out.writeBytes(bytes);
        }
        return out.toByteArray();
    }
}
