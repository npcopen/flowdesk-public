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

/** Canonical payload and identity helpers for the M8 notification wire. */
public final class NotificationWireCodec {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter MICROS = new java.time.format.DateTimeFormatterBuilder()
            .appendInstant(6).toFormatter();

    private NotificationWireCodec() {
    }

    public record Payload(String schemaVersion, UUID recipientUserId,
                          String notificationKind, String sourceEffectKey,
                          String sourceRefType, UUID sourceRefId,
                          String title, String body) {
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

    /** Build the protected fd_* properties carried alongside a payload. */
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

    public static byte[] deriveKey(String namespace, Object... fields) {
        Object[] all = new Object[fields.length + 1];
        all[0] = namespace;
        System.arraycopy(fields, 0, all, 1, fields.length);
        return sha256(frame(all));
    }

    public static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    public static String format(Instant instant) {
        return MICROS.format(instant.truncatedTo(ChronoUnit.MICROS));
    }

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

    public static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    public static UUID uuidFromBytes(byte[] value) {
        if (value == null || value.length != 16) {
            throw new IllegalArgumentException("UUID bytes must contain exactly 16 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /**
     * Derive the persisted UUID projection used by notification message ids
     * from a full SHA-256 identity key.  Keeping this separate from
     * {@link #uuidFromBytes(byte[])} prevents accidentally accepting a
     * truncated or malformed UUID column as a key.
     */
    public static UUID uuidFromKey(byte[] value) {
        if (value == null || value.length != 32) {
            throw new IllegalArgumentException("Identity key must contain exactly 32 bytes");
        }
        return uuidFromBytes(java.util.Arrays.copyOf(value, 16));
    }
}
