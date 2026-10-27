package com.flowdesk.alert.service;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 告警模块的确定性标识编码工具：相同命名空间、相同字段始终得到相同摘要。
 *
 * <p>命名空间区分建单、恢复、消息发送等用途；字段使用长度前缀而非直接拼接，
 * 避免不同字段边界产生歧义。这里生成的是幂等身份，不是使用密钥的签名或认证凭证。
 * 编码规则一旦用于持久化消息，就必须与消费者和人工重放保持兼容。
 */
public final class AlertIdempotencyCodec {
    /**
     * 禁止直接实例化该无状态工具类；调用方通过其静态方法或嵌套契约模型使用能力。
     */
    private AlertIdempotencyCodec() {}

    /**
     * 对命名空间、租户及业务资源作长度分帧并计算摘要，为跨重试保持稳定的业务意图身份。
     */
    public static byte[] derivedKey(String namespace, Object... values) {
        Object[] fields = new Object[values.length + 1];
        fields[0] = namespace;
        System.arraycopy(values, 0, fields, 1, values.length);
        return sha256(frame(fields));
    }

    /** 发送身份只由租户和 messageId 决定，重试时复用同一消息即可保持同一身份。 */
    public static byte[] publicationKey(UUID tenantId, UUID messageId) {
        return derivedKey("FD-PUB-V1", tenantId, messageId);
    }

    /**
     * 计算输入字节的 SHA-256 摘要；算法不可用时抛出服务端异常，不使用不安全的替代摘要。
     */
    public static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * 把字节按固定小写十六进制编码，用于消息属性、摘要展示和跨服务契约字段。
     */
    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** 取摘要前 16 字节构造确定性 UUID；此操作只做格式转换，不生成新的随机身份。 */
    public static UUID uuidFromDigest(byte[] digest) {
        ByteBuffer value = ByteBuffer.wrap(Arrays.copyOf(digest, 16));
        return new UUID(value.getLong(), value.getLong());
    }

    /** 每个字段先写入四字节长度再写内容，UUID 固定编码为 16 字节，避免不同字段组合散列为同一拼接文本。 */
    private static byte[] frame(Object... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object field : fields) {
            byte[] bytes;
            if (field instanceof UUID uuid) {
                bytes =
                        ByteBuffer.allocate(16)
                                .putLong(uuid.getMostSignificantBits())
                                .putLong(uuid.getLeastSignificantBits())
                                .array();
            } else if (field instanceof byte[] raw) bytes = raw;
            else bytes = field.toString().getBytes(StandardCharsets.UTF_8);
            out.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
            out.writeBytes(bytes);
        }
        return out.toByteArray();
    }
}
