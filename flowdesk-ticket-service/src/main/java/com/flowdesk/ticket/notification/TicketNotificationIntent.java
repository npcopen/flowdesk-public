package com.flowdesk.ticket.notification;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.notification.NotificationWireCodec;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Canonical, deliberately small Ticket-owned Notification intent.  The payload
 * contains only a safe title/body and immutable source references; it never
 * carries the Ticket description or an incoming transport body.
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

    private TicketNotificationIntent() {
    }

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

    /** Recipient is part of the child identity; a trigger root is never reused
     * as a publication key when a reminder/breach fans out to multiple users. */
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

    private static UUID uuid(byte[] key) {
        byte[] first = Arrays.copyOf(key, 16);
        ByteBuffer buffer = ByteBuffer.wrap(first);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public record Intent(UUID outboxId, UUID messageId, byte[] businessIntentKey,
                         byte[] publicationKey, byte[] sourceEffectKey,
                         String sourceRefType, UUID sourceRefId, UUID recipientUserId,
                         String notificationKind, byte[] bodyBytes, byte[] bodySha256,
                         UUID correlationId, UUID causationId, Instant occurredAt) {
        public Intent {
            businessIntentKey = businessIntentKey.clone();
            publicationKey = publicationKey.clone();
            sourceEffectKey = sourceEffectKey.clone();
            bodyBytes = bodyBytes.clone();
            bodySha256 = bodySha256.clone();
        }

        @Override public byte[] businessIntentKey() { return businessIntentKey.clone(); }
        @Override public byte[] publicationKey() { return publicationKey.clone(); }
        @Override public byte[] sourceEffectKey() { return sourceEffectKey.clone(); }
        @Override public byte[] bodyBytes() { return bodyBytes.clone(); }
        @Override public byte[] bodySha256() { return bodySha256.clone(); }
    }
}
