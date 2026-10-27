package com.flowdesk.ticket.notification;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.notification.NotificationWireCodec;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * 工单侧最小通知意图，只保存安全的标题、正文及不可变来源引用。
 * 不携带完整工单描述或外来消息体，收件人的通知身份由来源效果进一步派生。
 */
public final class TicketNotificationIntent {
    public static final String SOURCE_SERVICE = "flowdesk-ticket-service";
    public static final NotificationEventContract.Binding BINDING = NotificationEventContract.TICKET;

    public static final String ASSIGNED = "TICKET_ASSIGNED";
    public static final String TRANSFERRED = "TICKET_TRANSFERRED";
    public static final String RESOLVED = "TICKET_RESOLVED";
    public static final String REOPENED = "TICKET_REOPENED";
    public static final String APPROVAL_PASSED = "APPROVAL_PASSED";
    public static final String APPROVAL_REJECTED = "APPROVAL_REJECTED";
    public static final String APPROVAL_AUTO_PASSED = "APPROVAL_AUTO_PASSED";
    public static final String SLA_REMINDER = "SLA_REMINDER";
    public static final String SLA_BREACH = "SLA_BREACH";

    /**
     * 私有构造方法，阻止工具类实例化，调用方应使用静态入口。
     */
    private TicketNotificationIntent() {
    }

    /** 为具体收件人派生消息、Outbox 和发布身份，并将经过边界处理的文案编码为固定载荷。 */
    public static Intent create(UUID tenantId, UUID recipientUserId, String kind,
                                byte[] sourceEffectKey, String sourceRefType,
                                UUID sourceRefId, String title, String body,
                                UUID correlationId, UUID causationId, Instant occurredAt) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(recipientUserId, "recipientUserId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sourceEffectKey, "sourceEffectKey");
        Objects.requireNonNull(sourceRefType, "sourceRefType");
        Objects.requireNonNull(sourceRefId, "sourceRefId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        byte[] source = sourceEffectKey.clone();
        byte[] intentKey = deriveChildIntentKey(tenantId, source, recipientUserId, kind);
        byte[] publicationKey = deriveChildPublicationKey(tenantId, source, recipientUserId, kind);
        NotificationWireCodec.Payload payload = new NotificationWireCodec.Payload(
                "1", recipientUserId, kind, NotificationWireCodec.hex(source), sourceRefType,
                sourceRefId, bounded(title, 255), bounded(body, 2000));
        byte[] bodyBytes = NotificationWireCodec.encodePayload(payload);
        UUID messageId = uuid(publicationKey);
        UUID outboxId = uuid(NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-OUTBOX-V1", tenantId, publicationKey));
        return new Intent(outboxId, messageId, intentKey, publicationKey, source,
                sourceRefType, sourceRefId, recipientUserId, kind, bodyBytes,
                NotificationWireCodec.sha256(bodyBytes), correlationId, causationId,
                occurredAt);
    }

    /** 收件人参与子通知身份派生；提醒或超时通知扇出给多个用户时，不能直接复用触发根键作为发布键。 */
    public static byte[] deriveChildIntentKey(UUID tenantId, byte[] triggerEffectKey,
                                               UUID recipientUserId, String kind) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(triggerEffectKey, "triggerEffectKey");
        Objects.requireNonNull(recipientUserId, "recipientUserId");
        Objects.requireNonNull(kind, "kind");
        return NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-INTENT-V1", tenantId, triggerEffectKey,
                recipientUserId, kind);
    }

    /**
     * 从父身份派生子消息发布键，隔离不同用途并保持可重复计算。
     */
    public static byte[] deriveChildPublicationKey(UUID tenantId, byte[] triggerEffectKey,
                                                    UUID recipientUserId, String kind) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(triggerEffectKey, "triggerEffectKey");
        Objects.requireNonNull(recipientUserId, "recipientUserId");
        Objects.requireNonNull(kind, "kind");
        return NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-PUBLICATION-V1", tenantId, triggerEffectKey,
                recipientUserId, kind);
    }

    /**
     * 将输入限制于约定范围，避免异常参数扩大处理规模。
     */
    private static String bounded(String value, int max) {
        StringBuilder result = new StringBuilder(Math.min(value.length(), max));
        for (int i = 0; i < value.length() && result.length() < max; i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) {
                result.append(' ');
            }
            else {
                result.append(c);
            }
        }
        String normalized = result.toString().trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Notification text must not be blank");
        }
        return normalized;
    }

    /**
     * 转换业务标识与规范 UUID 字节表示，遵循既有空值与格式校验约定。
     */
    private static UUID uuid(byte[] key) {
        byte[] first = Arrays.copyOf(key, 16);
        ByteBuffer buffer = ByteBuffer.wrap(first);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /**
     * 通知规范意图快照，保存发布所需的业务身份、内容与摘要。
     */
    public record Intent(UUID outboxId, UUID messageId, byte[] businessIntentKey,
                         byte[] publicationKey, byte[] sourceEffectKey,
                         String sourceRefType, UUID sourceRefId, UUID recipientUserId,
                         String notificationKind, byte[] bodyBytes, byte[] bodySha256,
                         UUID correlationId, UUID causationId, Instant occurredAt) {
        /**
         * 保存记录字段，并对可变字节数组作防御性复制，避免调用方修改已构造的业务快照。
         */
        public Intent {
            businessIntentKey = businessIntentKey.clone();
            publicationKey = publicationKey.clone();
            sourceEffectKey = sourceEffectKey.clone();
            bodyBytes = bodyBytes.clone();
            bodySha256 = bodySha256.clone();
        }

        /**
         * 返回业务意图键的防御性副本，避免外部改写已保存的业务事实。
         */
        @Override public byte[] businessIntentKey() { return businessIntentKey.clone(); }
        /**
         * 返回消息发布幂等键的防御性副本，避免外部改写已保存的业务事实。
         */
        @Override public byte[] publicationKey() { return publicationKey.clone(); }
        /**
         * 返回来源副作用键的防御性副本，避免外部改写已保存的业务事实。
         */
        @Override public byte[] sourceEffectKey() { return sourceEffectKey.clone(); }
        /**
         * 返回消息原文字节的防御性副本，避免外部改写已保存的业务事实。
         */
        @Override public byte[] bodyBytes() { return bodyBytes.clone(); }
        /**
         * 返回消息 SHA-256 摘要的防御性副本，避免外部改写已保存的业务事实。
         */
        @Override public byte[] bodySha256() { return bodySha256.clone(); }
    }
}
