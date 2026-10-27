package com.flowdesk.workflow.start;

import com.flowdesk.workflow.model.recovery.WorkflowRecoveryModels;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 从不可变的 Inbox 来源事实恢复原始消息信封，使人工重放使用相同校验和消费路径。
 */
final class WorkflowStartReplayEnvelope {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter MICROSECOND_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
                    .withZone(ZoneOffset.UTC);

    /**
     * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
     */
    private WorkflowStartReplayEnvelope() {
    }

    /**
     * 从持久化快照恢复原始传输信封，保持消息身份、属性和字节一致。
     */
    static WorkflowStartTransportMessage restore(
            WorkflowRecoveryModels.ReplaySource source) {
        if (!WorkflowStartContract.PRODUCER_SERVICE.equals(source.producerService())
                || !WorkflowStartContract.CONTRACT_REF.equals(source.contractRef())
                || WorkflowStartContract.CONTRACT_VERSION != source.contractVersion()) {
            throw new IllegalStateException("Persisted Workflow recovery source contract mismatch");
        }
        UUID ticketId;
        try {
            JsonNode payload = JSON.readTree(source.bodyBytes());
            ticketId = UUID.fromString(payload.path("ticketId").stringValue());
        }
        catch (Exception invalidPersistedBody) {
            throw new IllegalStateException(
                    "Persisted Workflow recovery source body is not restorable",
                    invalidPersistedBody);
        }

        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("fd_envelope_version", WorkflowStartContract.ENVELOPE_VERSION);
        properties.put("fd_event_type", WorkflowStartContract.EVENT_TYPE);
        properties.put("fd_contract_ref", source.contractRef());
        properties.put("fd_contract_version", Integer.toString(source.contractVersion()));
        properties.put("fd_producer", source.producerService());
        properties.put("fd_tenant_id", source.tenantId().toString());
        properties.put("fd_message_id", source.messageId().toString());
        properties.put("fd_business_intent_key",
                HexFormat.of().formatHex(source.businessIntentKey()));
        properties.put("fd_aggregate_type", WorkflowStartContract.AGGREGATE_TYPE);
        properties.put("fd_aggregate_id", ticketId.toString());
        properties.put("fd_correlation_id", source.correlationId().toString());
        if (source.causationId() != null) {
            properties.put("fd_causation_id", source.causationId().toString());
        }
        properties.put("fd_occurred_at", MICROSECOND_UTC.format(source.occurredAt()));
        properties.put("fd_body_sha256", HexFormat.of().formatHex(source.bodySha256()));
        return new WorkflowStartTransportMessage(
                WorkflowStartContract.TOPIC,
                WorkflowStartContract.CONSUMER_GROUP,
                source.messageId().toString(),
                null,
                source.bodyBytes(),
                properties);
    }

}
