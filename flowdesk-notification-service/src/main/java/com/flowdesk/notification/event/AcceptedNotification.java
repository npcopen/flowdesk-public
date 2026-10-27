package com.flowdesk.notification.event;

import com.flowdesk.common.notification.NotificationEventContract;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/** 严格校验通过的通知消息，保存业务字段、传输身份和不可变原始报文供消费持久化。 */
public record AcceptedNotification(
        NotificationEventContract.Binding binding,
        UUID tenantId,
        UUID messageId,
        String messageKey,
        byte[] businessIntentKey,
        byte[] publicationKey,
        UUID aggregateId,
        UUID recipientUserId,
        String notificationKind,
        byte[] sourceEffectKey,
        String sourceRefType,
        UUID sourceRefId,
        String title,
        String body,
        UUID correlationId,
        UUID causationId,
        Instant envelopeOccurredAt,
        byte[] bodyBytes,
        byte[] bodySha256) {
    /**
     * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
     */
    public AcceptedNotification {
        java.util.Objects.requireNonNull(binding, "binding");
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(messageId, "messageId");
        java.util.Objects.requireNonNull(messageKey, "messageKey");
        businessIntentKey = businessIntentKey.clone();
        publicationKey = publicationKey.clone();
        sourceEffectKey = sourceEffectKey.clone();
        bodyBytes = bodyBytes.clone();
        bodySha256 = bodySha256.clone();
    }

    /** 兼容旧调用方将 messageId 作为消息 key 的构造方式。 */
    public AcceptedNotification(NotificationEventContract.Binding binding,
                                UUID tenantId,
                                UUID messageId,
                                byte[] businessIntentKey,
                                byte[] publicationKey,
                                UUID aggregateId,
                                UUID recipientUserId,
                                String notificationKind,
                                byte[] sourceEffectKey,
                                String sourceRefType,
                                UUID sourceRefId,
                                String title,
                                String body,
                                UUID correlationId,
                                UUID causationId,
                                Instant envelopeOccurredAt,
                                byte[] bodyBytes,
                                byte[] bodySha256) {
        this(binding, tenantId, messageId, messageId.toString(), businessIntentKey,
                publicationKey, aggregateId, recipientUserId, notificationKind,
                sourceEffectKey, sourceRefType, sourceRefId, title, body,
                correlationId, causationId, envelopeOccurredAt, bodyBytes, bodySha256);
    }

    /**
     * 返回业务意图键的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override public byte[] businessIntentKey() { return businessIntentKey.clone(); }
    /**
     * 返回消息发布键的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override public byte[] publicationKey() { return publicationKey.clone(); }
    /**
     * 返回来源副作用键的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override public byte[] sourceEffectKey() { return sourceEffectKey.clone(); }
    /**
     * 返回原始消息字节的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override public byte[] bodyBytes() { return bodyBytes.clone(); }
    /**
     * 返回消息体摘要的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override public byte[] bodySha256() { return bodySha256.clone(); }

    /** 为持久化及重放路径返回已经校验的不可变信封。 */
    public NotificationEnvelope envelope() {
        return new NotificationEnvelope(binding, tenantId, messageId, messageKey,
                businessIntentKey, publicationKey, NotificationContract.AGGREGATE_TYPE,
                aggregateId, correlationId, causationId, envelopeOccurredAt, bodyBytes, bodySha256,
                false);
    }
}
