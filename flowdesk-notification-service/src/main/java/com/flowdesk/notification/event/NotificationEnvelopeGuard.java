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

/** 先完整校验路由、信封和原始报文，再校验收件人与业务意图；明确区分传输拒绝和租户内确定性拒绝。 */
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

    /**
     * 按主队列绑定校验信封、载荷和摘要，全部通过后才返回可信消息。
     */
    public AcceptedNotification validateMain(NotificationTransportMessage message) {
        return validate(message, bindingForMain(message), false);
    }

    /**
     * 按死信绑定核对原始消息，防止错误队列或不完整信封进入恢复。
     */
    public AcceptedNotification validateDlq(NotificationTransportMessage message) {
        return validate(message, bindingForDlq(message), true);
    }

    /**
     * 按主队列 Topic 与消费组定位冻结契约，未知路由在信任租户前被拒绝。
     */
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

    /**
     * 按死信 Topic 与接纳消费组定位原契约，避免将死信误当主队列消息。
     */
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

    /**
     * 逐层核验路由、属性、摘要和载荷，明确区分租户可信前后的异常边界。
     */
    private AcceptedNotification validate(NotificationTransportMessage message,
                                          NotificationEventContract.Binding binding,
                                          boolean dlq) {
        requirePre(binding != null, "CONSUMER_DIRECTION_MISMATCH");
        requirePre(message.keys() != null && !message.keys().isBlank()
                        && message.keys().length() <= 128,
                "MESSAGE_KEY_INVALID");
        // 固定契约不使用 Tags，额外标签必须拒绝。
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
        // 未知载荷字段属于传输结构错误；下面的字段语义错误才归类为租户内的确定性拒绝。
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

    /**
     * 在租户可信前解析 JSON 与结构，非法字节或字段不产生租户业务事实。
     */
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

    /**
     * 严格按 UTF-8 解码消息体，拒绝不符合冻结传输契约的字节序列。
     */
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

    /**
     * 读取可信信封阶段必填文本，格式错误时保留信封供记录租户内拒绝事实。
     */
    private static String trustedText(JsonNode node, String field,
                                      NotificationEnvelope envelope) {
        JsonNode value = node.get(field);
        trustedRequire(value != null && value.isString(), "PAYLOAD_TYPE_INVALID:" + field, envelope);
        return value.stringValue();
    }

    /**
     * 读取可信信封阶段必填文本，格式错误时保留信封供记录租户内拒绝事实。
     */
    private static void trustedText(JsonNode node, String field, String expected,
                                    NotificationEnvelope envelope) {
        trustedRequire(expected.equals(trustedText(node, field, envelope)),
                "PAYLOAD_VALUE_INVALID:" + field, envelope);
    }

    /**
     * 解析载荷 UUID 并检查规范格式，非法值转换为确定性契约错误。
     */
    private static UUID payloadUuid(JsonNode node, String field, NotificationEnvelope envelope) {
        return uuidTrusted(trustedText(node, field, envelope), field, envelope);
    }

    /**
     * 解析载荷中的十六进制键或摘要，核验长度及编码后返回字节值。
     */
    private static byte[] payloadHex(JsonNode node, String field, NotificationEnvelope envelope) {
        return hexTrusted(trustedText(node, field, envelope), field, envelope);
    }

    /**
     * 在租户信任建立前解析契约时间字段，拒绝非规范时间表示。
     */
    private static Instant instantPre(String value, String field) {
        requirePre(value != null && MICROS.matcher(value).matches(), "UTC_FORMAT_INVALID:" + field);
        try {
            return Instant.parse(value);
        }
        catch (RuntimeException e) {
            throw pre("UTC_FORMAT_INVALID:" + field, e);
        }
    }

    /**
     * 在租户可信前解析 UUID，避免从非法身份字段派生租户上下文。
     */
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

    /**
     * 解析可信阶段的 UUID，失败时保留信封以便持久化拒绝事实。
     */
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

    /**
     * 在租户可信前核验十六进制字段格式和长度，拒绝非法摘要或业务键。
     */
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

    /**
     * 解析可信信封的十六进制字段，错误附带原信封上下文供记录拒绝。
     */
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

    /**
     * 在租户可信前核验固定字段值，拒绝不兼容协议进入业务处理。
     */
    private static void fixedPre(Map<String, String> values, String key, String expected) {
        requirePre(expected.equals(values.get(key)), "ENVELOPE_VALUE_INVALID:" + key);
    }

    /**
     * 检查契约文本的字符和长度限制，防止非法内容进入后续校验。
     */
    private static boolean safeText(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return false;
        }
        return true;
    }

    /**
     * 核验错误码格式与长度，保证其可安全用于存储和错误响应。
     */
    private static String boundedCode(String value) {
        if (value == null) return "unknown";
        String clean = value.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return clean.substring(0, Math.min(96, clean.length()));
    }

    /**
     * 在租户可信前检查条件，失败时抛出不含可信租户事实的异常。
     */
    private static void requirePre(boolean condition, String code) {
        if (!condition) throw pre(code);
    }

    /**
     * 在租户已可信后检查条件，失败时保留信封供记录拒绝事实。
     */
    private static void trustedRequire(boolean condition, String code,
                                       NotificationEnvelope envelope) {
        if (!condition) throw trusted(code, envelope);
    }

    /**
     * 构造尚未建立可信租户上下文的异常，供入口按非法传输报文处置。
     */
    private static NotificationContractViolation pre(String code) {
        return new NotificationContractViolation(code);
    }

    /**
     * 构造尚未建立可信租户上下文的异常，供入口按非法传输报文处置。
     */
    private static NotificationContractViolation pre(String code, Throwable cause) {
        return new NotificationContractViolation(code, cause);
    }

    /**
     * 构造携带可信信封的异常，供入口在相应租户中记录拒绝事实。
     */
    private static NotificationContractViolation trusted(String code,
                                                         NotificationEnvelope envelope) {
        return NotificationContractViolation.trusted(code, envelope);
    }

    /**
     * 构造携带可信信封的异常，供入口在相应租户中记录拒绝事实。
     */
    private static NotificationContractViolation trusted(String code, Throwable cause,
                                                         NotificationEnvelope envelope) {
        return NotificationContractViolation.trusted(code, cause, envelope);
    }
}
