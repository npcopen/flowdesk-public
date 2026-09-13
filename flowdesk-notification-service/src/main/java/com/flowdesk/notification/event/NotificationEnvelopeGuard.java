package com.flowdesk.notification.event;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.notification.NotificationWireCodec;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict Notification transport validation.
 *
 * <p>The envelope is validated completely before a tenant context can be
 * installed. Only after that boundary is crossed are payload/recipient
 * invariants classified as a tenant-owned deterministic rejection.</p>
 */
public final class NotificationEnvelopeGuard {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final UUID ZERO = new UUID(0L, 0L);
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MICROS = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z");
    private static final Set<String> REQUIRED_ENVELOPE = Set.of(
            "fd_envelope_version", "fd_event_type", "fd_contract_ref",
            "fd_contract_version", "fd_producer", "fd_tenant_id", "fd_message_id",
            "fd_business_intent_key", "fd_publication_key", "fd_aggregate_type",
            "fd_aggregate_id", "fd_correlation_id", "fd_occurred_at", "fd_body_sha256");
    private static final Set<String> ALLOWED_ENVELOPE = Set.of(
            "fd_envelope_version", "fd_event_type", "fd_contract_ref",
            "fd_contract_version", "fd_producer", "fd_tenant_id", "fd_message_id",
            "fd_business_intent_key", "fd_publication_key", "fd_aggregate_type",
            "fd_aggregate_id", "fd_correlation_id", "fd_causation_id",
            "fd_occurred_at", "fd_body_sha256");
    private static final Set<String> PAYLOAD_FIELDS = Set.of(
            "schemaVersion", "recipientUserId", "notificationKind", "sourceEffectKey",
            "sourceRefType", "sourceRefId", "title", "body");

    public AcceptedNotification validateMain(NotificationTransportMessage message) {
        return validate(message, bindingForMain(message), false);
    }

    public AcceptedNotification validateDlq(NotificationTransportMessage message) {
        return validate(message, bindingForDlq(message), true);
    }

    private static NotificationEventContract.Binding bindingForMain(
            NotificationTransportMessage message) {
        requirePre(message != null, "DELIVERY_MISSING");
        for (NotificationEventContract.Binding binding : NotificationContract.bindings()) {
            if (binding.topic().equals(message.topic())
                    && binding.consumerGroup().equals(message.consumerGroup())) {
                return binding;
            }
        }
        throw pre("CONSUMER_DIRECTION_MISMATCH");
    }

    private static NotificationEventContract.Binding bindingForDlq(
            NotificationTransportMessage message) {
        requirePre(message != null, "DELIVERY_MISSING");
        for (NotificationEventContract.Binding binding : NotificationContract.bindings()) {
            if (binding.dlqTopic().equals(message.topic())
                    && binding.dlqAcceptorGroup().equals(message.consumerGroup())) {
                return binding;
            }
        }
        throw pre("CONSUMER_DIRECTION_MISMATCH");
    }

    private AcceptedNotification validate(NotificationTransportMessage message,
                                          NotificationEventContract.Binding binding,
                                          boolean dlq) {
        requirePre(binding != null, "CONSUMER_DIRECTION_MISMATCH");
        requirePre(message.keys() != null && !message.keys().isBlank()
                        && message.keys().length() <= 128,
                "MESSAGE_KEY_INVALID");
        // Tags are intentionally absent from the frozen contract.
        requirePre(message.tags() == null, "TAG_FORBIDDEN");

        Map<String, String> properties = message.userProperties();
        requirePre(properties != null, "ENVELOPE_PROPERTIES_MISSING");
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String name = entry.getKey();
            if (name == null || (name.startsWith("fd_") && !ALLOWED_ENVELOPE.contains(name))) {
                throw pre(name == null ? "NULL_ENVELOPE_PROPERTY" :
                        "UNKNOWN_ENVELOPE_PROPERTY:" + boundedCode(name));
            }
            if (name.startsWith("fd_") && (entry.getValue() == null
                    || entry.getValue().length() > 512)) {
                throw pre("ENVELOPE_PROPERTY_INVALID:" + boundedCode(name));
            }
        }
        for (String field : REQUIRED_ENVELOPE) {
            requirePre(properties.containsKey(field)
                            && properties.get(field) != null
                            && !properties.get(field).isBlank(),
                    "MISSING_ENVELOPE_PROPERTY:" + field);
        }

        fixedPre(properties, "fd_envelope_version", NotificationContract.ENVELOPE_VERSION);
        fixedPre(properties, "fd_event_type", binding.eventType());
        fixedPre(properties, "fd_contract_ref", binding.contractRef());
        fixedPre(properties, "fd_contract_version", Integer.toString(NotificationContract.VERSION));
        fixedPre(properties, "fd_producer", binding.producerService());
        fixedPre(properties, "fd_aggregate_type", NotificationContract.AGGREGATE_TYPE);

        UUID tenantId = uuidPre(properties.get("fd_tenant_id"), "fd_tenant_id");
        UUID messageId = uuidPre(properties.get("fd_message_id"), "fd_message_id");
        UUID aggregateId = uuidPre(properties.get("fd_aggregate_id"), "fd_aggregate_id");
        UUID correlationId = uuidPre(properties.get("fd_correlation_id"), "fd_correlation_id");
        requirePre(!ZERO.equals(tenantId), "TENANT_ID_ZERO_FORBIDDEN");
        requirePre(!ZERO.equals(messageId), "MESSAGE_ID_ZERO_FORBIDDEN");
        requirePre(!ZERO.equals(aggregateId), "AGGREGATE_ID_ZERO_FORBIDDEN");
        requirePre(!ZERO.equals(correlationId), "CORRELATION_ID_ZERO_FORBIDDEN");

        UUID causationId = null;
        if (properties.containsKey("fd_causation_id")) {
            causationId = uuidPre(properties.get("fd_causation_id"), "fd_causation_id");
            requirePre(!ZERO.equals(causationId), "CAUSATION_ID_ZERO_FORBIDDEN");
        }
        requirePre(messageId.toString().equals(message.keys()), "MESSAGE_KEY_MISMATCH");

        Instant occurredAt = instantPre(properties.get("fd_occurred_at"), "fd_occurred_at");
        byte[] businessIntent = hexPre(properties.get("fd_business_intent_key"),
                "fd_business_intent_key");
        byte[] publication = hexPre(properties.get("fd_publication_key"),
                "fd_publication_key");
        byte[] declaredHash = hexPre(properties.get("fd_body_sha256"), "fd_body_sha256");
        byte[] body = message.body();
        requirePre(body != null && body.length > 0, "BODY_MISSING");
        requirePre(body.length <= NotificationContract.MAX_BODY_BYTES, "BODY_TOO_LARGE");
        decodeUtf8Pre(body);
        byte[] bodyHash = NotificationWireCodec.sha256(body);
        requirePre(java.security.MessageDigest.isEqual(bodyHash, declaredHash),
                "BODY_HASH_MISMATCH");
        requirePre(messageId.equals(NotificationWireCodec.uuidFromKey(publication)),
                "MESSAGE_PUBLICATION_ID_MISMATCH");

        NotificationEnvelope envelope = new NotificationEnvelope(binding, tenantId, messageId,
                message.keys(), businessIntent, publication, NotificationContract.AGGREGATE_TYPE,
                aggregateId, correlationId, causationId, occurredAt, body, bodyHash, dlq);

        JsonNode payload = parsePayloadPre(body);
        // Unknown payload fields are structural wire errors; semantic field
        // errors below are deterministic tenant-owned rejections.
        payload.properties().forEach(entry -> {
            if (!PAYLOAD_FIELDS.contains(entry.getKey())) {
                throw pre("PAYLOAD_UNKNOWN_FIELD:" + boundedCode(entry.getKey()));
            }
        });
        for (String field : PAYLOAD_FIELDS) {
            trustedRequire(payload.get(field) != null, "PAYLOAD_FIELD_MISSING:" + field, envelope);
        }

        trustedText(payload, "schemaVersion", "1", envelope);
        UUID recipient = payloadUuid(payload, "recipientUserId", envelope);
        trustedRequire(!ZERO.equals(recipient), "RECIPIENT_ZERO_FORBIDDEN", envelope);
        String kind = trustedText(payload, "notificationKind", envelope);
        trustedRequire(kind.length() <= 64 && !kind.isBlank() && safeText(kind),
                "NOTIFICATION_KIND_INVALID", envelope);
        byte[] sourceEffect = payloadHex(payload, "sourceEffectKey", envelope);
        String sourceRefType = trustedText(payload, "sourceRefType", envelope);
        trustedRequire(sourceRefType.length() <= 48 && !sourceRefType.isBlank()
                        && safeText(sourceRefType), "SOURCE_REF_TYPE_INVALID", envelope);
        UUID sourceRefId = payloadUuid(payload, "sourceRefId", envelope);
        String title = trustedText(payload, "title", envelope);
        String bodyText = trustedText(payload, "body", envelope);
        trustedRequire(title.length() <= 255 && !title.isBlank() && safeText(title),
                "TITLE_INVALID", envelope);
        trustedRequire(bodyText.length() <= 2000 && !bodyText.isBlank() && safeText(bodyText),
                "BODY_TEXT_INVALID", envelope);

        byte[] expectedIntent = NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-INTENT-V1", tenantId, sourceEffect, recipient, kind);
        trustedRequire(java.security.MessageDigest.isEqual(expectedIntent, businessIntent),
                "BUSINESS_INTENT_KEY_MISMATCH", envelope);
        byte[] expectedPublication = NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-PUBLICATION-V1", tenantId, sourceEffect, recipient, kind);
        trustedRequire(java.security.MessageDigest.isEqual(expectedPublication, publication),
                "PUBLICATION_KEY_MISMATCH", envelope);
        trustedRequire(sourceRefId.equals(aggregateId), "AGGREGATE_PAYLOAD_MISMATCH", envelope);

        return new AcceptedNotification(binding, tenantId, messageId, message.keys(),
                businessIntent, publication,
                aggregateId, recipient, kind, sourceEffect, sourceRefType, sourceRefId, title,
                bodyText, correlationId, causationId, occurredAt, body, bodyHash);
    }

    private static JsonNode parsePayloadPre(byte[] bytes) {
        try {
            String value = decodeUtf8Pre(bytes);
            try (JsonParser parser = JSON.createParser(value)) {
                JsonNode node = JSON.readTree(parser);
                requirePre(node != null && node.isObject(), "PAYLOAD_TYPE_INVALID:root");
                requirePre(parser.nextToken() == null, "PAYLOAD_TRAILING_CONTENT");
                return node;
            }
        }
        catch (NotificationContractViolation e) {
            throw e;
        }
        catch (Exception e) {
            throw pre("PAYLOAD_INVALID_JSON", e);
        }
    }

    private static String decodeUtf8Pre(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        }
        catch (Exception e) {
            throw pre("BODY_NOT_UTF8", e);
        }
    }

    private static String trustedText(JsonNode node, String field,
                                      NotificationEnvelope envelope) {
        JsonNode value = node.get(field);
        trustedRequire(value != null && value.isString(), "PAYLOAD_TYPE_INVALID:" + field, envelope);
        return value.stringValue();
    }

    private static void trustedText(JsonNode node, String field, String expected,
                                    NotificationEnvelope envelope) {
        trustedRequire(expected.equals(trustedText(node, field, envelope)),
                "PAYLOAD_VALUE_INVALID:" + field, envelope);
    }

    private static UUID payloadUuid(JsonNode node, String field, NotificationEnvelope envelope) {
        return uuidTrusted(trustedText(node, field, envelope), field, envelope);
    }

    private static byte[] payloadHex(JsonNode node, String field, NotificationEnvelope envelope) {
        return hexTrusted(trustedText(node, field, envelope), field, envelope);
    }

    private static Instant instantPre(String value, String field) {
        requirePre(value != null && MICROS.matcher(value).matches(), "UTC_FORMAT_INVALID:" + field);
        try {
            return Instant.parse(value);
        }
        catch (RuntimeException e) {
            throw pre("UTC_FORMAT_INVALID:" + field, e);
        }
    }

    private static UUID uuidPre(String value, String field) {
        requirePre(value != null && UUID_TEXT.matcher(value).matches(),
                "UUID_FORMAT_INVALID:" + field);
        try {
            UUID result = UUID.fromString(value);
            requirePre(result.toString().equals(value), "UUID_FORMAT_INVALID:" + field);
            return result;
        }
        catch (RuntimeException e) {
            throw pre("UUID_FORMAT_INVALID:" + field, e);
        }
    }

    private static UUID uuidTrusted(String value, String field, NotificationEnvelope envelope) {
        trustedRequire(value != null && UUID_TEXT.matcher(value).matches(),
                "UUID_FORMAT_INVALID:" + field, envelope);
        try {
            UUID result = UUID.fromString(value);
            trustedRequire(result.toString().equals(value), "UUID_FORMAT_INVALID:" + field, envelope);
            return result;
        }
        catch (NotificationContractViolation e) {
            throw e;
        }
        catch (RuntimeException e) {
            throw trusted("UUID_FORMAT_INVALID:" + field, e, envelope);
        }
    }

    private static byte[] hexPre(String value, String field) {
        requirePre(value != null && HEX.matcher(value).matches(),
                "SHA256_FORMAT_INVALID:" + field);
        try {
            return HexFormat.of().parseHex(value);
        }
        catch (RuntimeException e) {
            throw pre("SHA256_FORMAT_INVALID:" + field, e);
        }
    }

    private static byte[] hexTrusted(String value, String field, NotificationEnvelope envelope) {
        trustedRequire(value != null && HEX.matcher(value).matches(),
                "SHA256_FORMAT_INVALID:" + field, envelope);
        try {
            return HexFormat.of().parseHex(value);
        }
        catch (RuntimeException e) {
            throw trusted("SHA256_FORMAT_INVALID:" + field, e, envelope);
        }
    }

    private static void fixedPre(Map<String, String> values, String key, String expected) {
        requirePre(expected.equals(values.get(key)), "ENVELOPE_VALUE_INVALID:" + key);
    }

    private static boolean safeText(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return false;
        }
        return true;
    }

    private static String boundedCode(String value) {
        if (value == null) return "unknown";
        String clean = value.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return clean.substring(0, Math.min(96, clean.length()));
    }

    private static void requirePre(boolean condition, String code) {
        if (!condition) throw pre(code);
    }

    private static void trustedRequire(boolean condition, String code,
                                       NotificationEnvelope envelope) {
        if (!condition) throw trusted(code, envelope);
    }

    private static NotificationContractViolation pre(String code) {
        return new NotificationContractViolation(code);
    }

    private static NotificationContractViolation pre(String code, Throwable cause) {
        return new NotificationContractViolation(code, cause);
    }

    private static NotificationContractViolation trusted(String code,
                                                         NotificationEnvelope envelope) {
        return NotificationContractViolation.trusted(code, envelope);
    }

    private static NotificationContractViolation trusted(String code, Throwable cause,
                                                         NotificationEnvelope envelope) {
        return NotificationContractViolation.trusted(code, cause, envelope);
    }
}
