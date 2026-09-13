package com.flowdesk.notification.event;

import com.flowdesk.common.notification.NotificationEventContract;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/** Values emitted by the strict transport guard after all wire checks pass. */
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

    /** Source-compatible constructor for callers that used messageId as the key. */
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

    @Override public byte[] businessIntentKey() { return businessIntentKey.clone(); }
    @Override public byte[] publicationKey() { return publicationKey.clone(); }
    @Override public byte[] sourceEffectKey() { return sourceEffectKey.clone(); }
    @Override public byte[] bodyBytes() { return bodyBytes.clone(); }
    @Override public byte[] bodySha256() { return bodySha256.clone(); }

    /** Returns the already verified immutable envelope for persistence/replay paths. */
    public NotificationEnvelope envelope() {
        return new NotificationEnvelope(binding, tenantId, messageId, messageKey,
                businessIntentKey, publicationKey, NotificationContract.AGGREGATE_TYPE,
                aggregateId, correlationId, causationId, envelopeOccurredAt, bodyBytes, bodySha256,
                false);
    }
}
