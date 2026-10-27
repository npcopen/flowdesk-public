package com.flowdesk.alert.event;

import com.flowdesk.alert.service.AlertIdempotencyCodec;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 工单关联结果进入 Alert 业务层前的消息契约校验器，主队列和死信队列共用相同的内容规则。
 *
 * <p>先检查投递主题、消费组和固定信封字段，再核对原始报文摘要、JSON 字段类型、故障标识及派生业务意图。
 * 通过后才生成 AcceptedTicketResult，供消费者建立租户上下文。这里不查询数据库；建单意图是否匹配已有
 * 故障、ticketId 是否与现有关联冲突，仍由事务内的业务检查决定。
 */
final class TicketResultEnvelopeGuard {
    private static final ObjectMapper JSON =
            JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final Set<String> REQUIRED =
            Set.of(
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
    private static final Set<String> ALLOWED = new HashSet<>(REQUIRED);

    static {
        ALLOWED.add("fd_causation_id");
    }

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MICROS =
            Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z");

    /** 主队列必须同时匹配正式主题与正式消费组，防止其他消息方向误入本入口。 */
    AcceptedTicketResult validateMain(TicketResultTransportMessage m) {
        return validate(
                m,
                AlertEventContract.TICKET_ESTABLISHED.topic(),
                AlertEventContract.TICKET_ESTABLISHED.consumerGroup());
    }

    /** 死信使用独立主题和接纳组，但不能放宽原事件的信封与正文校验。 */
    AcceptedTicketResult validateDlq(TicketResultTransportMessage m) {
        return validate(
                m,
                AlertEventContract.TICKET_ESTABLISHED.dlqTopic(),
                AlertEventContract.TICKET_ESTABLISHED.dlqAcceptorGroup());
    }

    /**
     * 核对传输 Topic、Tag、生产者、契约版本及全部信封字段，再解析载荷身份与摘要，生成可进入租户处理的验证结果。
     */
    private AcceptedTicketResult validate(
            TicketResultTransportMessage m, String topic, String group) {
        req(m != null, "DELIVERY_MISSING");
        req(topic.equals(m.topic()), "TOPIC_MISMATCH");
        req(group.equals(m.consumerGroup()), "CONSUMER_DIRECTION_MISMATCH");
        req(m.tags() == null, "TAG_FORBIDDEN");
        Map<String, String> p = m.properties();
        // 只约束本协议拥有的 fd_ 属性；Broker 自带的其他属性不作为未知信封字段拒绝。
        for (String key : REQUIRED) req(p.containsKey(key), "MISSING_ENVELOPE_PROPERTY:" + key);
        for (String key : p.keySet())
            if (key != null && key.startsWith("fd_") && !ALLOWED.contains(key))
                throw bad("UNKNOWN_ENVELOPE_PROPERTY:" + key);
        fixed(p, "fd_envelope_version", "1");
        fixed(p, "fd_event_type", AlertEventContract.TICKET_ESTABLISHED.eventType());
        fixed(p, "fd_contract_ref", AlertEventContract.TICKET_ESTABLISHED.contractRef());
        fixed(p, "fd_contract_version", "1");
        fixed(p, "fd_producer", AlertEventContract.TICKET_ESTABLISHED.producer());
        fixed(p, "fd_aggregate_type", "INCIDENT");
        UUID tenant = uuid(p.get("fd_tenant_id"), "fd_tenant_id"),
                message = uuid(p.get("fd_message_id"), "fd_message_id"),
                incident = uuid(p.get("fd_aggregate_id"), "fd_aggregate_id"),
                correlation = uuid(p.get("fd_correlation_id"), "fd_correlation_id");
        UUID causation =
                p.containsKey("fd_causation_id")
                        ? uuid(p.get("fd_causation_id"), "fd_causation_id")
                        : null;
        req(message.toString().equals(m.keys()), "MESSAGE_KEY_MISMATCH");
        Instant envelopeAt = instant(p.get("fd_occurred_at"), "fd_occurred_at");
        // 摘要针对收到的原始字节计算，不能先解析并重新序列化 JSON，否则会掩盖报文内容差异。
        byte[] body = m.body();
        req(body != null && body.length > 0, "BODY_MISSING");
        req(body.length <= AlertEventContract.MAX_BODY_BYTES, "BODY_TOO_LARGE");
        byte[] hash = AlertIdempotencyCodec.sha256(body);
        req(
                MessageDigest.isEqual(hash, hex(p.get("fd_body_sha256"), "fd_body_sha256")),
                "BODY_HASH_MISMATCH");
        JsonNode payload = parse(body);
        req(payload.isObject(), "PAYLOAD_TYPE_INVALID:root");
        Set<String> fields =
                Set.of(
                        "schemaVersion",
                        "incidentId",
                        "createTicketIntentKey",
                        "ticketId",
                        "establishedAt");
        for (String field : fields)
            req(payload.get(field) != null, "PAYLOAD_FIELD_MISSING:" + field);
        text(payload, "schemaVersion", "1");
        UUID payloadIncident = payloadUuid(payload, "incidentId");
        req(incident.equals(payloadIncident), "AGGREGATE_PAYLOAD_MISMATCH");
        byte[] createIntent = payloadHex(payload, "createTicketIntentKey");
        UUID ticket = payloadUuid(payload, "ticketId");
        Instant established = payloadInstant(payload, "establishedAt");
        byte[] intent = hex(p.get("fd_business_intent_key"), "fd_business_intent_key");
        // 结果事件的业务身份由租户和故障确定；payload 中的 createIntent 另由消费者核对原建单记录。
        byte[] expected =
                AlertIdempotencyCodec.derivedKey(
                        AlertEventContract.TICKET_ESTABLISHED.intentNamespace(), tenant, incident);
        req(MessageDigest.isEqual(intent, expected), "BUSINESS_INTENT_KEY_MISMATCH");
        return new AcceptedTicketResult(
                tenant,
                message,
                intent,
                incident,
                createIntent,
                ticket,
                established,
                correlation,
                causation,
                envelopeAt,
                body,
                hash);
    }

    /** 拒绝非法 UTF-8、重复 JSON 键及根值后的多余内容，避免同一报文被不同解析方式赋予不同含义。 */
    private static JsonNode parse(byte[] body) {
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(body))
                            .toString();
            try (var parser = JSON.createParser(text)) {
                JsonNode value = JSON.readTree(parser);
                req(parser.nextToken() == null, "PAYLOAD_TRAILING_CONTENT");
                return value;
            }
        } catch (TicketResultContractViolation expected) {
            throw expected;
        } catch (Exception e) {
            throw bad("PAYLOAD_INVALID_JSON", e);
        }
    }

    /**
     * 读取并校验载荷中的必填 UUID 字段，类型或格式错误转换为消息契约违规。
     */
    private static UUID payloadUuid(JsonNode n, String f) {
        req(n.get(f) != null && n.get(f).isString(), "PAYLOAD_TYPE_INVALID:" + f);
        return uuid(n.get(f).stringValue(), f);
    }

    /**
     * 读取并校验载荷摘要字段，要求满足十六进制格式及固定字节长度。
     */
    private static byte[] payloadHex(JsonNode n, String f) {
        req(n.get(f) != null && n.get(f).isString(), "PAYLOAD_TYPE_INVALID:" + f);
        return hex(n.get(f).stringValue(), f);
    }

    /**
     * 读取并校验载荷时间字段，要求格式符合冻结消息契约，再转换为 Instant。
     */
    private static Instant payloadInstant(JsonNode n, String f) {
        req(n.get(f) != null && n.get(f).isString(), "PAYLOAD_TYPE_INVALID:" + f);
        return instant(n.get(f).stringValue(), f);
    }

    /**
     * 读取消息对象中的文本字段，拒绝缺失、类型错误或不符合字符要求的值。
     */
    private static void text(JsonNode n, String f, String v) {
        req(
                n.get(f) != null && n.get(f).isString() && v.equals(n.get(f).stringValue()),
                "PAYLOAD_VALUE_INVALID:" + f);
    }

    /**
     * 核对消息字段与冻结契约的固定值一致，防止其他契约或生产者借用当前消费路径。
     */
    private static void fixed(Map<String, String> p, String f, String v) {
        req(v.equals(p.get(f)), "ENVELOPE_VALUE_INVALID:" + f);
    }

    /**
     * 严格解析规范 UUID 字符串，拒绝不满足契约格式的身份字段。
     */
    private static UUID uuid(String v, String f) {
        req(v != null && UUID_TEXT.matcher(v).matches(), "UUID_FORMAT_INVALID:" + f);
        try {
            UUID x = UUID.fromString(v);
            req(x.toString().equals(v), "UUID_FORMAT_INVALID:" + f);
            return x;
        } catch (RuntimeException e) {
            throw bad("UUID_FORMAT_INVALID:" + f, e);
        }
    }

    /**
     * 将受校验时间文本转换为 Instant，格式错误使用当前接口约定的参数或契约错误处理。
     */
    private static Instant instant(String v, String f) {
        req(v != null && MICROS.matcher(v).matches(), "UTC_FORMAT_INVALID:" + f);
        try {
            return Instant.parse(v);
        } catch (RuntimeException e) {
            throw bad("UTC_FORMAT_INVALID:" + f, e);
        }
    }

    /**
     * 把字节按固定小写十六进制编码，用于消息属性、摘要展示和跨服务契约字段。
     */
    private static byte[] hex(String v, String f) {
        req(v != null && HEX.matcher(v).matches(), "SHA256_FORMAT_INVALID:" + f);
        return HexFormat.of().parseHex(v);
    }

    /**
     * 在消息校验条件不满足时抛出契约违规，使尚未可信的载荷不能进入租户业务处理。
     */
    private static void req(boolean c, String code) {
        if (!c) throw bad(code);
    }

    /**
     * 构造带稳定错误码的契约违规异常，按需保留底层解析原因供诊断。
     */
    private static TicketResultContractViolation bad(String c) {
        return new TicketResultContractViolation(c);
    }

    /**
     * 构造带稳定错误码的契约违规异常，按需保留底层解析原因供诊断。
     */
    private static TicketResultContractViolation bad(String c, Throwable e) {
        return new TicketResultContractViolation(c, e);
    }
}
