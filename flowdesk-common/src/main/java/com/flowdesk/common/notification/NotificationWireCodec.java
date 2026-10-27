package com.flowdesk.common.notification;

import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 通知消息的规范化编解码与身份工具，统一消息摘要、受保护属性和持久化标识的计算方式。
 */
public final class NotificationWireCodec {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter MICROS = new java.time.format.DateTimeFormatterBuilder()
            .appendInstant(6).toFormatter();

    /**
     * 禁止直接实例化该工具类型，统一通过静态方法或工厂入口使用。
     */
    private NotificationWireCodec() {
    }

    /**
     * 通知消息的不可变契约数据，包含接收人、业务效果键、来源引用及展示正文。
     */
    public record Payload(String schemaVersion, UUID recipientUserId,
                          String notificationKind, String sourceEffectKey,
                          String sourceRefType, UUID sourceRefId,
                          String title, String body) {
        /**
         * 校验通知必需字段均非空，并将缺失或空白 schemaVersion 规范为版本 1。
         */
        public Payload {
            Objects.requireNonNull(recipientUserId, "recipientUserId");
            Objects.requireNonNull(notificationKind, "notificationKind");
            Objects.requireNonNull(sourceEffectKey, "sourceEffectKey");
            Objects.requireNonNull(sourceRefType, "sourceRefType");
            Objects.requireNonNull(sourceRefId, "sourceRefId");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(body, "body");
            if (schemaVersion == null || schemaVersion.isBlank()) {
                schemaVersion = "1";
            }
        }
    }

    /**
     * 按固定契约字段编码通知 JSON；结果超过 64 KiB 时拒绝发送，序列化失败转为内部状态异常。
     */
    public static byte[] encodePayload(Payload payload) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("schemaVersion", payload.schemaVersion());
        values.put("recipientUserId", payload.recipientUserId().toString());
        values.put("notificationKind", payload.notificationKind());
        values.put("sourceEffectKey", payload.sourceEffectKey());
        values.put("sourceRefType", payload.sourceRefType());
        values.put("sourceRefId", payload.sourceRefId().toString());
        values.put("title", payload.title());
        values.put("body", payload.body());
        try {
            byte[] body = JSON.writeValueAsBytes(values);
            if (body.length > NotificationEventContract.MAX_BODY_BYTES) {
                throw new IllegalArgumentException("notification payload exceeds 64 KiB");
            }
            return body;
        }
        catch (IllegalArgumentException e) {
            throw e;
        }
        catch (Exception e) {
            throw new IllegalStateException("Unable to encode notification payload", e);
        }
    }

    /** 构造随消息载荷传递的受保护 fd_* 属性，用于消费者校验消息身份。 */
    public static Map<String, String> envelope(NotificationEventContract.Binding binding,
                                                UUID tenantId, UUID messageId,
                                                byte[] businessIntentKey,
                                                byte[] publicationKey, UUID aggregateId,
                                                UUID correlationId, UUID causationId,
                                                Instant occurredAt, byte[] body) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(businessIntentKey, "businessIntentKey");
        Objects.requireNonNull(publicationKey, "publicationKey");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(body, "body");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("fd_envelope_version", NotificationEventContract.ENVELOPE_VERSION);
        values.put("fd_event_type", binding.eventType());
        values.put("fd_contract_ref", binding.contractRef());
        values.put("fd_contract_version", Integer.toString(NotificationEventContract.VERSION));
        values.put("fd_producer", binding.producerService());
        values.put("fd_tenant_id", tenantId.toString());
        values.put("fd_message_id", messageId.toString());
        values.put("fd_business_intent_key", hex(businessIntentKey));
        values.put("fd_publication_key", hex(publicationKey));
        values.put("fd_aggregate_type", "NOTIFICATION_REQUEST");
        values.put("fd_aggregate_id", aggregateId.toString());
        values.put("fd_correlation_id", correlationId.toString());
        if (causationId != null) values.put("fd_causation_id", causationId.toString());
        values.put("fd_occurred_at", format(occurredAt));
        values.put("fd_body_sha256", hex(sha256(body)));
        return Map.copyOf(values);
    }

    /**
     * 将命名空间和业务字段按长度分帧后求 SHA-256，生成稳定且不受字段拼接歧义影响的键。
     */
    public static byte[] deriveKey(String namespace, Object... fields) {
        Object[] all = new Object[fields.length + 1];
        all[0] = namespace;
        System.arraycopy(fields, 0, all, 1, fields.length);
        return sha256(frame(all));
    }

    /**
     * 计算输入字节的 SHA-256 摘要；标准算法不可用时抛出内部状态异常。
     */
    public static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /**
     * 将字节转换为统一十六进制字符串，供摘要、消息键和诊断输出使用。
     */
    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * 将时间截断为微秒后按固定格式输出，保持数据库精度与消息契约一致。
     */
    public static String format(Instant instant) {
        return MICROS.format(instant.truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * 按字段写入四字节长度及内容；UUID 使用二进制，文本采用 UTF-8，避免字段边界歧义。
     */
    public static byte[] frame(Object... fields) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (Object field : fields) {
            byte[] bytes;
            if (field instanceof UUID uuid) {
                bytes = uuidBytes(uuid);
            }
            else if (field instanceof byte[] raw) {
                bytes = raw;
            }
            else {
                bytes = String.valueOf(field).getBytes(StandardCharsets.UTF_8);
            }
            output.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
            output.writeBytes(bytes);
        }
        return output.toByteArray();
    }

    /**
     * 按高位在前的顺序编码 UUID 为 16 字节，与持久化及摘要编码保持一致。
     */
    public static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    /**
     * 检查输入恰好为 16 字节后还原 UUID，空值或错误长度直接拒绝。
     */
    public static UUID uuidFromBytes(byte[] value) {
        if (value == null || value.length != 16) {
            throw new IllegalArgumentException("UUID bytes must contain exactly 16 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /**
     * 从完整的 SHA-256 身份键派生通知消息持久化使用的 UUID 投影。
     * 与 {@link #uuidFromBytes(byte[])} 分开，防止将截断或格式错误的 UUID 列误当成身份键。
     */
    public static UUID uuidFromKey(byte[] value) {
        if (value == null || value.length != 32) {
            throw new IllegalArgumentException("Identity key must contain exactly 32 bytes");
        }
        return uuidFromBytes(java.util.Arrays.copyOf(value, 16));
    }
}
