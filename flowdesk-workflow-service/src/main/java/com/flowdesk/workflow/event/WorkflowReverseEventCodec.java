package com.flowdesk.workflow.event;

import com.flowdesk.workflow.service.WorkflowIdempotencyCodec;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 按固定字段顺序生成紧凑 UTF-8 字节，用于实例建立与审批完成两个独立事件契约。
 */
public final class WorkflowReverseEventCodec {

    private static final DateTimeFormatter MICROS = new java.time.format.DateTimeFormatterBuilder()
            .appendInstant(6).toFormatter();

    /**
     * 禁止直接实例化工具容器，相关静态规则与转换由调用方按业务需要复用。
     */
    private WorkflowReverseEventCodec() {
    }

    /**
     * 按冻结字段生成实例建立事件及稳定消息身份，保留供工单侧关联的原业务事实。
     */
    public static byte[] instanceEstablished(UUID ticketId, UUID workflowInstanceId,
                                             byte[] startIntentKey, UUID definitionId,
                                             UUID workflowVersionId, int versionNo,
                                             String instanceStatus, Instant startedAt) {
        String json = "{" +
                "\"schemaVersion\":\"1\"," +
                "\"ticketId\":\"" + ticketId + "\"," +
                "\"workflowInstanceId\":\"" + workflowInstanceId + "\"," +
                "\"startIntentKey\":\"" + HexFormat.of().formatHex(startIntentKey) + "\"," +
                "\"workflowDefinitionId\":\"" + definitionId + "\"," +
                "\"workflowVersionId\":\"" + workflowVersionId + "\"," +
                "\"versionNo\":" + versionNo + "," +
                "\"instanceStatus\":\"" + instanceStatus + "\"," +
                "\"startedAt\":\"" + format(startedAt) + "\"}";
        return checked(json);
    }

    /**
     * 按冻结契约编码审批完成事件，保留审批结论及申请人需要的说明文本。
     */
    public static byte[] approvalFinalized(UUID ticketId, UUID workflowInstanceId,
                                           byte[] startIntentKey, UUID definitionId,
                                           UUID workflowVersionId, int versionNo,
                                           String outcome, Instant completedAt) {
        return approvalFinalized(ticketId, workflowInstanceId, startIntentKey, definitionId,
                workflowVersionId, versionNo, outcome, completedAt, null);
    }

    /**
     * 为无审批任务的自动通过路径编码可选条件结果；常规审批省略该字段，以保持既有消息字节。
     */
    public static byte[] approvalFinalized(UUID ticketId, UUID workflowInstanceId,
                                           byte[] startIntentKey, UUID definitionId,
                                           UUID workflowVersionId, int versionNo,
                                           String outcome, Instant completedAt,
                                           Boolean conditionResult) {
        return approvalFinalized(ticketId, workflowInstanceId, startIntentKey, definitionId,
                workflowVersionId, versionNo, outcome, completedAt, conditionResult, null);
    }

    /**
     * 按冻结契约编码审批完成事件，保留审批结论及申请人需要的说明文本。
     */
    public static byte[] approvalFinalized(UUID ticketId, UUID workflowInstanceId,
                                           byte[] startIntentKey, UUID definitionId,
                                           UUID workflowVersionId, int versionNo,
                                           String outcome, Instant completedAt,
                                           Boolean conditionResult, String decisionReason) {
        String json = "{" +
                "\"schemaVersion\":\"1\"," +
                "\"ticketId\":\"" + ticketId + "\"," +
                "\"workflowInstanceId\":\"" + workflowInstanceId + "\"," +
                "\"startIntentKey\":\"" + HexFormat.of().formatHex(startIntentKey) + "\"," +
                "\"workflowDefinitionId\":\"" + definitionId + "\"," +
                "\"workflowVersionId\":\"" + workflowVersionId + "\"," +
                "\"versionNo\":" + versionNo + "," +
                (conditionResult == null ? "" : "\"conditionResult\":" + conditionResult + ",") +
                "\"outcome\":\"" + outcome + "\"," +
                (decisionReason == null ? "" : "\"decisionReason\":" +
                        new tools.jackson.databind.ObjectMapper().writeValueAsString(decisionReason) + ",") +
                "\"completedAt\":\"" + format(completedAt) + "\"}";
        return checked(json);
    }

    /**
     * 根据租户与实例派生实例建立事件的稳定意图键，重投保持身份不变。
     */
    public static byte[] instanceEstablishedIntent(UUID tenantId, UUID ticketId) {
        return WorkflowIdempotencyCodec.derivedKey(
                WorkflowReverseEventContract.InstanceEstablished.BUSINESS_INTENT_NAMESPACE,
                tenantId, ticketId);
    }

    /**
     * 根据租户与实例派生审批完成事件的稳定意图键，重复完成沿用原事件身份。
     */
    public static byte[] approvalFinalizedIntent(UUID tenantId, UUID workflowInstanceId) {
        return WorkflowIdempotencyCodec.derivedKey(
                WorkflowReverseEventContract.ApprovalFinalized.BUSINESS_INTENT_NAMESPACE,
                tenantId, workflowInstanceId);
    }

    /**
     * 反向事件的发布身份由不可变业务意图派生，不在事务重试时重新分配；
     * Broker 启动消费和人工审批路径共用同一规则。
     */
    public static UUID outboxId(UUID tenantId, String contractRef, byte[] businessIntentKey) {
        return WorkflowIdempotencyCodec.uuidFromDigest(WorkflowIdempotencyCodec.derivedKey(
                "FD-WORKFLOW-REVERSE-OUTBOX-V1", tenantId, contractRef, businessIntentKey));
    }

    /**
     * 从事件稳定业务身份派生消息标识，避免重复序列化改变投递身份。
     */
    public static UUID messageId(UUID tenantId, String contractRef, byte[] businessIntentKey) {
        return WorkflowIdempotencyCodec.uuidFromDigest(WorkflowIdempotencyCodec.derivedKey(
                "FD-WORKFLOW-REVERSE-MESSAGE-V1", tenantId, contractRef, businessIntentKey));
    }

    /**
     * 按冻结契约格式输出时间或文本，使跨服务编码结果可稳定校验。
     */
    private static String format(Instant value) {
        return MICROS.format(value.truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * 核验编码事件满足冻结契约，再交给 Outbox 持久化或传输。
     */
    private static byte[] checked(String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        if (body.length > WorkflowReverseEventContract.MAX_BODY_BYTES) {
            throw new IllegalArgumentException("M6 event payload exceeds 64 KiB");
        }
        return body;
    }
}
