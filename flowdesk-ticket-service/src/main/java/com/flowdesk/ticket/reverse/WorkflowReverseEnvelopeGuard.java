package com.flowdesk.ticket.reverse;

import com.flowdesk.ticket.service.IdempotencyCodec;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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

/** 在信任租户字段前，严格校验两类流程反向事件的传输身份、受保护属性和载荷。 */
public final class WorkflowReverseEnvelopeGuard {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MICROS = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z");
    private static final Set<String> REQUIRED = Set.of(
            "fd_envelope_version", "fd_event_type", "fd_contract_ref",
            "fd_contract_version", "fd_producer", "fd_tenant_id", "fd_message_id",
            "fd_business_intent_key", "fd_aggregate_type", "fd_aggregate_id",
            "fd_correlation_id", "fd_occurred_at", "fd_body_sha256");
    private static final Set<String> ALLOWED = Set.of(
            "fd_envelope_version", "fd_event_type", "fd_contract_ref",
            "fd_contract_version", "fd_producer", "fd_tenant_id", "fd_message_id",
            "fd_business_intent_key", "fd_aggregate_type", "fd_aggregate_id",
            "fd_correlation_id", "fd_causation_id", "fd_occurred_at", "fd_body_sha256");

    /**
     * 校验主消息路由、生产者及信封，生成可进入租户业务处理的验证结果。
     */
    public AcceptedWorkflowReverse validateMain(WorkflowReverseTransportMessage message) {
        WorkflowReverseContract.Binding binding = bindingForMain(message);
        return validate(message, binding, binding.topic(), binding.consumerGroup());
    }

    /**
     * 校验死信原始消息契约，供接收器建立可恢复 Inbox。
     */
    public AcceptedWorkflowReverse validateDlq(WorkflowReverseTransportMessage message) {
        WorkflowReverseContract.Binding binding = bindingForDlq(message);
        return validate(message, binding, binding.dlqTopic(), binding.dlqAcceptorGroup());
    }

    /**
     * 按主消息主题定位契约绑定，供路由和信封校验。
     */
    private static WorkflowReverseContract.Binding bindingForMain(
            WorkflowReverseTransportMessage message) {
        require(message != null, "DELIVERY_MISSING");
        for (var binding : new WorkflowReverseContract.Binding[]{
                WorkflowReverseContract.INSTANCE_ESTABLISHED,
                WorkflowReverseContract.APPROVAL_FINALIZED}) {
            if (binding.consumerGroup().equals(message.consumerGroup())
                    && binding.topic().equals(message.topic())) return binding;
        }
        throw violation("CONSUMER_DIRECTION_MISMATCH");
    }

    /**
     * 按死信主题定位原始绑定，供恢复接收校验。
     */
    private static WorkflowReverseContract.Binding bindingForDlq(
            WorkflowReverseTransportMessage message) {
        require(message != null, "DELIVERY_MISSING");
        for (var binding : new WorkflowReverseContract.Binding[]{
                WorkflowReverseContract.INSTANCE_ESTABLISHED,
                WorkflowReverseContract.APPROVAL_FINALIZED}) {
            if (binding.dlqAcceptorGroup().equals(message.consumerGroup())
                    && binding.dlqTopic().equals(message.topic())) return binding;
        }
        throw violation("CONSUMER_DIRECTION_MISMATCH");
    }

    /**
     * 核验工作流反向结果输入结构、身份与契约字段，拒绝不完整或不兼容内容。
     */
    private AcceptedWorkflowReverse validate(WorkflowReverseTransportMessage message,
                                               WorkflowReverseContract.Binding binding,
                                               String topic, String group) {
        require(topic.equals(message.topic()), "TOPIC_MISMATCH");
        require(group.equals(message.consumerGroup()), "CONSUMER_DIRECTION_MISMATCH");
        require(message.tags() == null, "TAG_FORBIDDEN");
        Map<String, String> properties = message.userProperties();
        for (String field : REQUIRED) require(properties.containsKey(field),
                "MISSING_ENVELOPE_PROPERTY:" + field);
        for (String field : properties.keySet()) {
            if (field != null && field.startsWith("fd_") && !ALLOWED.contains(field)) {
                throw violation("UNKNOWN_ENVELOPE_PROPERTY:" + field);
            }
        }
        fixed(properties, "fd_envelope_version", WorkflowReverseContract.ENVELOPE_VERSION);
        fixed(properties, "fd_event_type", binding.eventType());
        fixed(properties, "fd_contract_ref", binding.contractRef());
        fixed(properties, "fd_contract_version", Integer.toString(WorkflowReverseContract.VERSION));
        fixed(properties, "fd_producer", WorkflowReverseContract.PRODUCER);
        fixed(properties, "fd_aggregate_type", WorkflowReverseContract.AGGREGATE_TYPE);

        UUID tenantId = uuid(properties.get("fd_tenant_id"), "fd_tenant_id");
        UUID messageId = uuid(properties.get("fd_message_id"), "fd_message_id");
        UUID aggregateId = uuid(properties.get("fd_aggregate_id"), "fd_aggregate_id");
        UUID correlationId = uuid(properties.get("fd_correlation_id"), "fd_correlation_id");
        UUID causationId = properties.containsKey("fd_causation_id")
                ? uuid(properties.get("fd_causation_id"), "fd_causation_id") : null;
        require(causationId == null || !new UUID(0, 0).equals(causationId),
                "CAUSATION_ID_ZERO_FORBIDDEN");
        require(messageId.toString().equals(message.keys()), "MESSAGE_KEY_MISMATCH");
        Instant envelopeOccurred = instant(properties.get("fd_occurred_at"), "fd_occurred_at");

        byte[] body = message.body();
        require(body != null && body.length > 0, "BODY_MISSING");
        require(body.length <= WorkflowReverseContract.MAX_BODY_BYTES, "BODY_TOO_LARGE");
        byte[] bodyHash = sha256(body);
        byte[] declaredHash = hex(properties.get("fd_body_sha256"), "fd_body_sha256");
        require(MessageDigest.isEqual(bodyHash, declaredHash), "BODY_HASH_MISMATCH");
        JsonNode payload = parse(body);
        require(payload != null && payload.isObject(), "PAYLOAD_TYPE_INVALID:root");

        Set<String> commonPayload = Set.of("schemaVersion", "ticketId", "workflowInstanceId",
                "startIntentKey", "workflowDefinitionId", "workflowVersionId", "versionNo");
        for (String field : commonPayload) {
            require(payload.get(field) != null, "PAYLOAD_FIELD_MISSING:" + field);
        }
        Set<String> allowedPayload = binding.kind() == WorkflowReverseContract.Kind.INSTANCE_ESTABLISHED
                ? Set.of("schemaVersion", "ticketId", "workflowInstanceId", "startIntentKey",
                "workflowDefinitionId", "workflowVersionId", "versionNo", "instanceStatus", "startedAt")
                : Set.of("schemaVersion", "ticketId", "workflowInstanceId", "startIntentKey",
                "workflowDefinitionId", "workflowVersionId", "versionNo", "outcome", "completedAt",
                "conditionResult", "decisionReason");
        payload.properties().forEach(entry ->
                require(allowedPayload.contains(entry.getKey()),
                        "UNKNOWN_PAYLOAD_FIELD:" + entry.getKey()));
        text(payload, "schemaVersion", "1");
        UUID ticketId = payloadUuid(payload, "ticketId");
        UUID instanceId = payloadUuid(payload, "workflowInstanceId");
        UUID definitionId = payloadUuid(payload, "workflowDefinitionId");
        UUID versionId = payloadUuid(payload, "workflowVersionId");
        byte[] startIntent = payloadHex(payload, "startIntentKey");
        JsonNode versionNode = payload.get("versionNo");
        require(versionNode.isIntegralNumber() && versionNode.canConvertToInt()
                && versionNode.intValue() > 0, "PAYLOAD_TYPE_INVALID:versionNo");
        require(instanceId.equals(aggregateId), "AGGREGATE_PAYLOAD_MISMATCH");

        String instanceStatus = null;
        String outcome = null;
        Boolean conditionResult = null;
        Instant businessOccurred;
        if (binding.kind() == WorkflowReverseContract.Kind.INSTANCE_ESTABLISHED) {
            require(payload.get("instanceStatus") != null, "PAYLOAD_FIELD_MISSING:instanceStatus");
            require(payload.get("startedAt") != null, "PAYLOAD_FIELD_MISSING:startedAt");
            instanceStatus = textOneOf(payload, "instanceStatus", "RUNNING", "COMPLETED_PASSED");
            businessOccurred = payloadInstant(payload, "startedAt");
        }
        else {
            require(payload.get("outcome") != null, "PAYLOAD_FIELD_MISSING:outcome");
            require(payload.get("completedAt") != null, "PAYLOAD_FIELD_MISSING:completedAt");
            outcome = textOneOf(payload, "outcome", "PASSED", "REJECTED");
            if (payload.get("decisionReason") != null) {
                var reason = payload.get("decisionReason");
                require(reason.isString() && !reason.stringValue().isBlank()
                        && reason.stringValue().length() <= 1000
                        && reason.stringValue().chars().noneMatch(Character::isISOControl),
                        "PAYLOAD_VALUE_INVALID:decisionReason");
            }
            businessOccurred = payloadInstant(payload, "completedAt");
            if (payload.get("conditionResult") != null) {
                require(payload.get("conditionResult").isBoolean(),
                        "PAYLOAD_TYPE_INVALID:conditionResult");
                conditionResult = payload.get("conditionResult").booleanValue();
                require("PASSED".equals(outcome) && !conditionResult,
                        "PAYLOAD_VALUE_INVALID:conditionResult");
            }
        }

        byte[] businessIntent = hex(properties.get("fd_business_intent_key"),
                "fd_business_intent_key");
        byte[] expectedIntent = binding.kind() == WorkflowReverseContract.Kind.INSTANCE_ESTABLISHED
                ? IdempotencyCodec.derivedKey(binding.intentNamespace(), tenantId, ticketId)
                : IdempotencyCodec.derivedKey(binding.intentNamespace(), tenantId, instanceId);
        require(MessageDigest.isEqual(businessIntent, expectedIntent),
                "BUSINESS_INTENT_KEY_MISMATCH");
        return new AcceptedWorkflowReverse(binding, tenantId, messageId, businessIntent,
                instanceId, ticketId, startIntent, definitionId, versionId,
                versionNode.intValue(), instanceStatus, outcome, conditionResult, businessOccurred,
                correlationId, causationId, envelopeOccurred, body, bodyHash);
    }

    /**
     * 解析规范输入并核验结构，拒绝无法恢复为契约字段的内容。
     */
    private static JsonNode parse(byte[] body) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
            try (JsonParser parser = JSON.createParser(text)) {
                JsonNode node = JSON.readTree(parser);
                require(parser.nextToken() == null, "PAYLOAD_TRAILING_CONTENT");
                return node;
            }
        }
        catch (WorkflowReverseContractViolation violation) { throw violation; }
        catch (Exception invalid) { throw violation("PAYLOAD_INVALID_JSON", invalid); }
    }

    /**
     * 验证字段等于冻结契约值，防止不兼容消息进入业务处理。
     */
    private static void fixed(Map<String, String> values, String field, String expected) {
        require(expected.equals(values.get(field)), "ENVELOPE_VALUE_INVALID:" + field);
    }
    /**
     * 读取必需文本字段，拒绝缺失或错误类型穿透消息边界。
     */
    private static void text(JsonNode node, String field, String expected) {
        require(node.get(field).isString(), "PAYLOAD_TYPE_INVALID:" + field);
        require(expected.equals(node.get(field).stringValue()), "PAYLOAD_VALUE_INVALID:" + field);
    }
    /**
     * 读取文本并验证属于冻结枚举集合，拒绝未知业务状态。
     */
    private static String textOneOf(JsonNode node, String field, String... allowed) {
        require(node.get(field).isString(), "PAYLOAD_TYPE_INVALID:" + field);
        String value = node.get(field).stringValue();
        require(java.util.Arrays.asList(allowed).contains(value), "PAYLOAD_VALUE_INVALID:" + field);
        return value;
    }
    /**
     * 解析载荷 UUID 字段，校验格式并拒绝不允许的占位值。
     */
    private static UUID payloadUuid(JsonNode node, String field) {
        require(node.get(field).isString(), "PAYLOAD_TYPE_INVALID:" + field);
        return uuid(node.get(field).stringValue(), field);
    }
    /**
     * 解析载荷十六进制字段并校验长度，供业务键和摘要核验。
     */
    private static byte[] payloadHex(JsonNode node, String field) {
        require(node.get(field).isString(), "PAYLOAD_TYPE_INVALID:" + field);
        return hex(node.get(field).stringValue(), field);
    }
    /**
     * 解析载荷时间字段，验证格式后恢复为 Instant。
     */
    private static Instant payloadInstant(JsonNode node, String field) {
        require(node.get(field).isString(), "PAYLOAD_TYPE_INVALID:" + field);
        return instant(node.get(field).stringValue(), field);
    }
    /**
     * 解析时间文本或转换数据库时间值，遵循空值约定并保留业务精度。
     */
    private static Instant instant(String value, String field) {
        require(value != null && MICROS.matcher(value).matches(), "UTC_FORMAT_INVALID:" + field);
        try { return Instant.parse(value); }
        catch (RuntimeException invalid) { throw violation("UTC_FORMAT_INVALID:" + field, invalid); }
    }
    /**
     * 转换业务标识与规范 UUID 字节表示，遵循既有空值与格式校验约定。
     */
    private static UUID uuid(String value, String field) {
        require(value != null && UUID_TEXT.matcher(value).matches(), "UUID_FORMAT_INVALID:" + field);
        try {
            UUID parsed = UUID.fromString(value);
            require(parsed.toString().equals(value), "UUID_FORMAT_INVALID:" + field);
            return parsed;
        }
        catch (RuntimeException invalid) { throw violation("UUID_FORMAT_INVALID:" + field, invalid); }
    }
    /**
     * 校验或转换十六进制内容，确保业务键与摘要具有预期表示。
     */
    private static byte[] hex(String value, String field) {
        require(value != null && HEX.matcher(value).matches(), "SHA256_FORMAT_INVALID:" + field);
        return HexFormat.of().parseHex(value);
    }
    /**
     * 计算输入内容 SHA-256 摘要，供消息完整性和业务意图核验。
     */
    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /**
     * 校验当前步骤要求的权限或契约条件，不满足时立即拒绝继续处理。
     */
    private static void require(boolean condition, String code) {
        if (!condition) throw violation(code);
    }
    /**
     * 构造带稳定错误码的契约异常，说明结构或内容不满足约束的原因。
     */
    private static WorkflowReverseContractViolation violation(String code) {
        return new WorkflowReverseContractViolation(code);
    }
    /**
     * 构造带稳定错误码的契约异常，说明结构或内容不满足约束的原因。
     */
    private static WorkflowReverseContractViolation violation(String code, Throwable cause) {
        return new WorkflowReverseContractViolation(code, cause);
    }
}
