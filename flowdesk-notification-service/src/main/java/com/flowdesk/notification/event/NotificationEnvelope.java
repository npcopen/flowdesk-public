package com.flowdesk.notification.event;

import com.flowdesk.common.notification.NotificationEventContract;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The immutable transport envelope that has crossed the tenant trust boundary.
 *
 * <p>Payload fields deliberately do not live here.  A caller may persist this
 * value for a deterministic rejection without treating an untrusted payload as
 * a business effect.</p>
 */
public record NotificationEnvelope(
        NotificationEventContract.Binding binding,
        UUID tenantId,
        UUID messageId,
        String messageKey,
        byte[] businessIntentKey,
        byte[] publicationKey,
        String aggregateType,
        UUID aggregateId,
        UUID correlationId,
        UUID causationId,
        Instant occurredAt,
        byte[] bodyBytes,
        byte[] bodySha256,
        boolean dlq) {

    public NotificationEnvelope {
        binding = Objects.requireNonNull(binding, "binding");
        tenantId = Objects.requireNonNull(tenantId, "tenantId");
        messageId = Objects.requireNonNull(messageId, "messageId");
        messageKey = Objects.requireNonNull(messageKey, "messageKey");
        businessIntentKey = Objects.requireNonNull(businessIntentKey, "businessIntentKey").clone();
        publicationKey = Objects.requireNonNull(publicationKey, "publicationKey").clone();
        aggregateType = Objects.requireNonNull(aggregateType, "aggregateType");
        aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        bodyBytes = Objects.requireNonNull(bodyBytes, "bodyBytes").clone();
        bodySha256 = Objects.requireNonNull(bodySha256, "bodySha256").clone();
    }

    @Override public byte[] businessIntentKey() { return businessIntentKey.clone(); }
    @Override public byte[] publicationKey() { return publicationKey.clone(); }
    @Override public byte[] bodyBytes() { return bodyBytes.clone(); }
    @Override public byte[] bodySha256() { return bodySha256.clone(); }

    public String topic() { return binding.topic(); }
    public String consumerGroup() { return binding.consumerGroup(); }
    public String logicalDestination() { return topic() + "|" + consumerGroup(); }
}
