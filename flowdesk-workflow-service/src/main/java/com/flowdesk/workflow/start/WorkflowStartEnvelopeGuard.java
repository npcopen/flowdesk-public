package com.flowdesk.workflow.start;

import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 确定性的传输契约校验器；必须在建立 Broker 租户上下文前执行，且不读取租户业务数据。
 */
public final class WorkflowStartEnvelopeGuard {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MICROSECOND_UTC = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z");
    private static final Set<String> REQUIRED_ENVELOPE = Set.of(
            "fd_envelope_version",
            "fd_event_type",
            "fd_contract_ref",
            "fd_contract_version",
            "fd_producer",
            "fd_tenant_id",
            "fd_message_id",
            "fd_business_intent_key",
            "fd_aggregate_type",
            "fd_aggregate_id",
            "fd_correlation_id",
            "fd_occurred_at",
            "fd_body_sha256");
    private static final Set<String> ALLOWED_ENVELOPE = Set.of(
            "fd_envelope_version",
            "fd_event_type",
            "fd_contract_ref",
            "fd_contract_version",
            "fd_producer",
            "fd_tenant_id",
            "fd_message_id",
            "fd_business_intent_key",
            "fd_aggregate_type",
            "fd_aggregate_id",
            "fd_correlation_id",
            "fd_causation_id",
            "fd_occurred_at",
            "fd_body_sha256");
    private static final Set<String> REQUIRED_PAYLOAD = Set.of(
            "schemaVersion",
            "ticketId",
            "workflowDefinitionId",
            "workflowVersionId",
            "versionNo",
            "serviceRiskLevel",
            "requesterUserId");

    /**
     * 核验冻结 Topic、身份、保留属性、载荷和摘要，通过全部约束后才返回可信启动消息。
     */
    public AcceptedWorkflowStart validate(WorkflowStartTransportMessage message) {
        return validateMain(message);
    }

    /**
     * 按主队列绑定校验信封、载荷和摘要，全部通过后才返回可信消息。
     */
    public AcceptedWorkflowStart validateMain(WorkflowStartTransportMessage message) {
        return validate(message, WorkflowStartContract.TOPIC,
                WorkflowStartContract.CONSUMER_GROUP);
    }

    /**
     * 按死信绑定核对原始消息，防止错误队列或不完整信封进入恢复。
     */
    public AcceptedWorkflowStart validateDlq(WorkflowStartTransportMessage message) {
        return validate(message, WorkflowStartContract.DLQ_TOPIC,
                WorkflowStartContract.DLQ_ACCEPTOR_GROUP);
    }

    /**
     * 核验冻结 Topic、身份、保留属性、载荷和摘要，通过全部约束后才返回可信启动消息。
     */
    private AcceptedWorkflowStart validate(WorkflowStartTransportMessage message,
                                           String expectedTopic,
                                           String expectedConsumerGroup) {
        require(message != null, "DELIVERY_MISSING");
        require(expectedTopic.equals(message.topic()), "TOPIC_MISMATCH");
        require(expectedConsumerGroup.equals(message.consumerGroup()),
                "CONSUMER_DIRECTION_MISMATCH");
        require(message.tags() == null, "TAG_FORBIDDEN");

        // 固定的 fd_ 属性采用白名单，缺失必填项或夹带未知字段都视为确定性契约错误。
        Map<String, String> properties = message.userProperties();
        for (String required : REQUIRED_ENVELOPE) {
            require(properties.containsKey(required),
                    "MISSING_ENVELOPE_PROPERTY:" + required);
        }
        for (String name : properties.keySet()) {
            if (name != null && name.startsWith("fd_") && !ALLOWED_ENVELOPE.contains(name)) {
                throw violation("UNKNOWN_ENVELOPE_PROPERTY:" + name);
            }
        }

        requireFixed(properties, "fd_envelope_version", WorkflowStartContract.ENVELOPE_VERSION);
        requireFixed(properties, "fd_event_type", WorkflowStartContract.EVENT_TYPE);
        requireFixed(properties, "fd_contract_ref", WorkflowStartContract.CONTRACT_REF);
        requireFixed(properties, "fd_contract_version",
                Integer.toString(WorkflowStartContract.CONTRACT_VERSION));
        requireFixed(properties, "fd_producer", WorkflowStartContract.PRODUCER_SERVICE);
        requireFixed(properties, "fd_aggregate_type", WorkflowStartContract.AGGREGATE_TYPE);

        UUID tenantId = canonicalUuid(properties.get("fd_tenant_id"), "fd_tenant_id");
        UUID messageId = canonicalUuid(properties.get("fd_message_id"), "fd_message_id");
        UUID aggregateId = canonicalUuid(properties.get("fd_aggregate_id"), "fd_aggregate_id");
        UUID correlationId = canonicalUuid(
                properties.get("fd_correlation_id"), "fd_correlation_id");
        UUID causationId = null;
        if (properties.containsKey("fd_causation_id")) {
            causationId = canonicalUuid(properties.get("fd_causation_id"), "fd_causation_id");
            require(!new UUID(0L, 0L).equals(causationId), "CAUSATION_ID_ZERO_FORBIDDEN");
        }
        require(messageId.toString().equals(message.keys()), "MESSAGE_KEY_MISMATCH");

        String occurredAtText = properties.get("fd_occurred_at");
        require(occurredAtText != null && MICROSECOND_UTC.matcher(occurredAtText).matches(),
                "OCCURRED_AT_FORMAT_INVALID");
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(occurredAtText);
        }
        catch (RuntimeException invalidTime) {
            throw violation("OCCURRED_AT_FORMAT_INVALID", invalidTime);
        }

        byte[] body = message.body();
        require(body != null && body.length > 0, "BODY_MISSING");
        require(body.length <= WorkflowStartContract.MAX_BODY_BYTES, "BODY_TOO_LARGE");
        String bodyHashText = properties.get("fd_body_sha256");
        require(bodyHashText != null && SHA256_HEX.matcher(bodyHashText).matches(),
                "BODY_HASH_FORMAT_INVALID");
        // 先校验原始消息字节的摘要，再解析 JSON，避免重序列化掩盖内容变化。
        byte[] actualBodyHash = sha256(body);
        require(MessageDigest.isEqual(HexFormat.of().parseHex(bodyHashText), actualBodyHash),
                "BODY_HASH_MISMATCH");

        JsonNode payload = parseUtf8Json(body);
        require(payload != null && payload.isObject(), "PAYLOAD_TYPE_INVALID:root");
        for (String field : REQUIRED_PAYLOAD) {
            require(payload.get(field) != null, "PAYLOAD_FIELD_MISSING:" + field);
        }
        requireText(payload, "schemaVersion", WorkflowStartContract.PAYLOAD_SCHEMA_VERSION);
        UUID ticketId = payloadUuid(payload, "ticketId");
        UUID workflowDefinitionId = payloadUuid(payload, "workflowDefinitionId");
        UUID workflowVersionId = payloadUuid(payload, "workflowVersionId");
        UUID requesterUserId = payloadUuid(payload, "requesterUserId");
        JsonNode versionNoNode = payload.get("versionNo");
        require(versionNoNode.isIntegralNumber()
                        && versionNoNode.canConvertToInt()
                        && versionNoNode.intValue() > 0,
                "PAYLOAD_TYPE_INVALID:versionNo");
        requireText(payload, "serviceRiskLevel", "HIGH");
        require(aggregateId.equals(ticketId), "AGGREGATE_PAYLOAD_MISMATCH");

        String businessIntentText = properties.get("fd_business_intent_key");
        require(businessIntentText != null && SHA256_HEX.matcher(businessIntentText).matches(),
                "BUSINESS_INTENT_KEY_FORMAT_INVALID");
        // 业务意图必须与租户和工单派生值一致，不能仅相信发送方声明的去重键。
        byte[] businessIntentKey = HexFormat.of().parseHex(businessIntentText);
        require(MessageDigest.isEqual(
                        businessIntentKey,
                        derivedKey(WorkflowStartContract.BUSINESS_INTENT_KEY_NAMESPACE,
                                tenantId, ticketId)),
                "BUSINESS_INTENT_KEY_MISMATCH");

        return new AcceptedWorkflowStart(
                tenantId,
                WorkflowStartContract.PRODUCER_SERVICE,
                messageId,
                businessIntentKey,
                ticketId,
                workflowDefinitionId,
                workflowVersionId,
                versionNoNode.intValue(),
                payload.get("serviceRiskLevel").stringValue(),
                requesterUserId,
                correlationId,
                causationId,
                occurredAt,
                WorkflowStartContract.CONTRACT_REF,
                WorkflowStartContract.CONTRACT_VERSION,
                body,
                actualBodyHash);
    }

    /**
     * 严格解码 UTF-8 JSON 并检查结构，避免自动探测编码放过非法消息。
     */
    private static JsonNode parseUtf8Json(byte[] body) {
        try {
            String utf8 = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
            try (JsonParser parser = JSON.createParser(utf8)) {
                JsonNode payload = JSON.readTree(parser);
                require(parser.nextToken() == null, "PAYLOAD_TRAILING_CONTENT");
                return payload;
            }
        }
        catch (WorkflowStartContractViolation violation) {
            throw violation;
        }
        catch (Exception invalidJson) {
            throw violation("PAYLOAD_INVALID_JSON", invalidJson);
        }
    }

    /**
     * 核对字段与冻结值一致，阻止不兼容消息继续处理。
     */
    private static void requireFixed(Map<String, String> properties,
                                     String name,
                                     String expected) {
        require(expected.equals(properties.get(name)), "ENVELOPE_VALUE_INVALID:" + name);
    }

    /**
     * 读取并核验契约必填文本，缺失或格式错误转换为确定性契约异常。
     */
    private static void requireText(JsonNode payload, String field, String expected) {
        JsonNode value = payload.get(field);
        require(value.isString(), "PAYLOAD_TYPE_INVALID:" + field);
        require(expected.equals(value.stringValue()), "PAYLOAD_VALUE_INVALID:" + field);
    }

    /**
     * 解析载荷 UUID 并检查规范格式，非法值转换为确定性契约错误。
     */
    private static UUID payloadUuid(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        require(value.isString(), "PAYLOAD_TYPE_INVALID:" + field);
        return canonicalUuid(value.stringValue(), field);
    }

    /**
     * 解析并核验 UUID 的规范文本，避免不同表示干扰消息身份校验。
     */
    private static UUID canonicalUuid(String value, String field) {
        require(value != null && CANONICAL_UUID.matcher(value).matches(),
                "UUID_FORMAT_INVALID:" + field);
        try {
            UUID parsed = UUID.fromString(value);
            require(parsed.toString().equals(value), "UUID_FORMAT_INVALID:" + field);
            return parsed;
        }
        catch (IllegalArgumentException invalidUuid) {
            throw violation("UUID_FORMAT_INVALID:" + field, invalidUuid);
        }
    }

    /**
     * 按固定命名域和组成部分派生业务键，避免不同用途间的键值碰撞。
     */
    private static byte[] derivedKey(String namespace, UUID tenantId, UUID ticketId) {
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        appendFramed(framed, namespace.getBytes(StandardCharsets.UTF_8));
        appendFramed(framed, uuidBytes(tenantId));
        appendFramed(framed, uuidBytes(ticketId));
        return sha256(framed.toByteArray());
    }

    /**
     * 追加带长度边界的字段，使摘要输入能明确表示每个业务组成部分。
     */
    private static void appendFramed(ByteArrayOutputStream target, byte[] field) {
        target.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(field.length).array());
        target.writeBytes(field);
    }

    /**
     * 将 UUID 编码为高位在前的十六字节值，供存储及摘要计算使用。
     */
    private static byte[] uuidBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    /**
     * 计算输入字节的 SHA-256 摘要，用于内容一致性核对及稳定业务键派生。
     */
    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 检查消息契约布尔条件，失败时以指定稳定错误码抛出契约异常。
     */
    private static void require(boolean condition, String code) {
        if (!condition) {
            throw violation(code);
        }
    }

    /**
     * 构造带稳定错误码的契约异常，供入口统一选择传输处置。
     */
    private static WorkflowStartContractViolation violation(String code) {
        return new WorkflowStartContractViolation(code);
    }

    /**
     * 构造带稳定错误码的契约异常，供入口统一选择传输处置。
     */
    private static WorkflowStartContractViolation violation(String code, Throwable cause) {
        return new WorkflowStartContractViolation(code, cause);
    }
}
