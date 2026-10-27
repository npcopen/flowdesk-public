package com.flowdesk.workflow.outbox;

import com.flowdesk.workflow.model.WorkflowOutboxModels;

import com.flowdesk.workflow.event.WorkflowReverseEventContract;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 直接从首次持久化的字节恢复反向事件信封，保持两个契约的固定消息格式。
 */
final class WorkflowOutboxEnvelopeFactory {
    private static final DateTimeFormatter MICROS = new DateTimeFormatterBuilder()
            .appendInstant(6).toFormatter(Locale.ROOT);

    /**
     * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
     */
    private WorkflowOutboxEnvelopeFactory() {
    }

    /**
     * 从已领取的持久化消息还原冻结信封，并核验身份、字节和摘要后交给传输层。
     */
    static WorkflowOutboxMessage create(WorkflowOutboxModels.Claimed row) {
        Binding binding = Binding.of(row.contractRef());
        if (row.contractVersion() != WorkflowReverseEventContract.CONTRACT_VERSION
                || !WorkflowReverseEventContract.AGGREGATE_TYPE.equals(row.aggregateType())) {
            throw new InvalidContract("Outbox contract identity is not frozen M6 v1");
        }
        byte[] body = row.bodyBytes();
        if (body.length == 0 || body.length > WorkflowReverseEventContract.MAX_BODY_BYTES) {
            throw new InvalidContract("Outbox body size is invalid");
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body));
        }
        catch (Exception invalid) {
            throw new InvalidContract("Outbox body is not UTF-8");
        }
        byte[] hash = sha256(body);
        if (!MessageDigest.isEqual(hash, row.bodySha256())
                || row.businessIntentKey().length != 32
                || row.publicationKey().length != 32) {
            throw new InvalidContract("Outbox persisted digests are invalid");
        }

        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("fd_envelope_version", WorkflowReverseEventContract.ENVELOPE_VERSION);
        properties.put("fd_event_type", binding.eventType());
        properties.put("fd_contract_ref", row.contractRef());
        properties.put("fd_contract_version", Integer.toString(row.contractVersion()));
        properties.put("fd_producer", WorkflowReverseEventContract.PRODUCER_SERVICE);
        properties.put("fd_tenant_id", row.tenantId().toString());
        properties.put("fd_message_id", row.messageId().toString());
        properties.put("fd_business_intent_key", HexFormat.of().formatHex(row.businessIntentKey()));
        properties.put("fd_aggregate_type", row.aggregateType());
        properties.put("fd_aggregate_id", row.aggregateId().toString());
        properties.put("fd_correlation_id", row.correlationId().toString());
        if (row.causationId() != null) {
            properties.put("fd_causation_id", row.causationId().toString());
        }
        properties.put("fd_occurred_at", MICROS.format(row.createdAt()));
        properties.put("fd_body_sha256", HexFormat.of().formatHex(hash));
        return new WorkflowOutboxMessage(
                binding.topic(), row.messageId().toString(), body, properties);
    }

    /**
     * 计算输入字节的 SHA-256 摘要，用于内容一致性核对及稳定业务键派生。
     */
    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * 工作流不可变数据快照，组合声明字段供当前业务处理阶段传递完整事实。
     */
    private record Binding(String contractRef, String eventType, String topic) {
        /**
         * 根据持久化反向事件的契约标识选择冻结绑定，未知类型拒绝发送。
         */
        static Binding of(String contractRef) {
            if (WorkflowReverseEventContract.InstanceEstablished.CONTRACT_REF.equals(contractRef)) {
                return new Binding(contractRef,
                        WorkflowReverseEventContract.InstanceEstablished.EVENT_TYPE,
                        WorkflowReverseEventContract.InstanceEstablished.TOPIC);
            }
            if (WorkflowReverseEventContract.ApprovalFinalized.CONTRACT_REF.equals(contractRef)) {
                return new Binding(contractRef,
                        WorkflowReverseEventContract.ApprovalFinalized.EVENT_TYPE,
                        WorkflowReverseEventContract.ApprovalFinalized.TOPIC);
            }
            throw new InvalidContract("Outbox contract is not an activated M6 reverse event");
        }
    }

    /**
     * 持久化消息不满足冻结契约时的异常，阻止不兼容信封继续发送。
     */
    static final class InvalidContract extends RuntimeException {
        /**
         * 创建异常或专用实现并保留传入原因，供调用方分类处理。
         */
        InvalidContract(String message) {
            super(message);
        }
    }
}
