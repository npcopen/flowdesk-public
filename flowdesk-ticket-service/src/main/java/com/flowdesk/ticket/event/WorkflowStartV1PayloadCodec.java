package com.flowdesk.ticket.event;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** 为 flowdesk.workflow.start.v1 编码规范 JSON，在发布前将确定的载荷字节持久化。 */
public final class WorkflowStartV1PayloadCodec {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 私有构造方法，阻止工具类实例化，调用方应使用静态入口。
     */
    private WorkflowStartV1PayloadCodec() {
    }

    /**
     * 按冻结协议编码业务载荷，生成发布与摘要计算使用的字节。
     */
    public static byte[] encode(UUID ticketId,
                                UUID workflowDefinitionId,
                                UUID workflowVersionId,
                                int versionNo,
                                UUID requesterUserId) {
        if (versionNo <= 0) {
            throw new IllegalArgumentException("versionNo must be positive");
        }

        Map<String, Object> payload = new TreeMap<>();
        payload.put("schemaVersion",
                TicketEventContract.WorkflowStartRequested.PAYLOAD_SCHEMA_VERSION);
        payload.put("ticketId", Objects.requireNonNull(ticketId, "ticketId").toString());
        payload.put("workflowDefinitionId", Objects.requireNonNull(
                workflowDefinitionId, "workflowDefinitionId").toString());
        payload.put("workflowVersionId", Objects.requireNonNull(
                workflowVersionId, "workflowVersionId").toString());
        payload.put("versionNo", versionNo);
        payload.put("serviceRiskLevel", "HIGH");
        payload.put("requesterUserId", Objects.requireNonNull(
                requesterUserId, "requesterUserId").toString());

        try {
            return JSON.writeValueAsBytes(payload);
        }
        catch (Exception ex) {
            throw new IllegalStateException("Unable to encode WorkflowStart.v1 payload", ex);
        }
    }
}
