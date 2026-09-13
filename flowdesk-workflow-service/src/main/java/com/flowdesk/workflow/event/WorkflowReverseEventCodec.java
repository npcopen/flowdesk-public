package com.flowdesk.workflow.event;

import com.flowdesk.workflow.service.WorkflowIdempotencyCodec;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

/** Fixed-order compact UTF-8 payload codec for the two independent M6 events. */
public final class WorkflowReverseEventCodec {

    private static final DateTimeFormatter MICROS = new java.time.format.DateTimeFormatterBuilder()
            .appendInstant(6).toFormatter();

    private WorkflowReverseEventCodec() {
    }

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

    public static byte[] approvalFinalized(UUID ticketId, UUID workflowInstanceId,
                                           byte[] startIntentKey, UUID definitionId,
                                           UUID workflowVersionId, int versionNo,
                                           String outcome, Instant completedAt) {
        return approvalFinalized(ticketId, workflowInstanceId, startIntentKey, definitionId,
                workflowVersionId, versionNo, outcome, completedAt, null);
    }

    /**
     * Encodes the optional condition result used by the no-task auto-pass path.
     * The field is omitted for ordinary approvals to preserve the M6 wire bytes.
     */
    public static byte[] approvalFinalized(UUID ticketId, UUID workflowInstanceId,
                                           byte[] startIntentKey, UUID definitionId,
                                           UUID workflowVersionId, int versionNo,
                                           String outcome, Instant completedAt,
                                           Boolean conditionResult) {
        return approvalFinalized(ticketId, workflowInstanceId, startIntentKey, definitionId,
                workflowVersionId, versionNo, outcome, completedAt, conditionResult, null);
    }

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

    public static byte[] instanceEstablishedIntent(UUID tenantId, UUID ticketId) {
        return WorkflowIdempotencyCodec.derivedKey(
                WorkflowReverseEventContract.InstanceEstablished.BUSINESS_INTENT_NAMESPACE,
                tenantId, ticketId);
    }

    public static byte[] approvalFinalizedIntent(UUID tenantId, UUID workflowInstanceId) {
        return WorkflowIdempotencyCodec.derivedKey(
                WorkflowReverseEventContract.ApprovalFinalized.BUSINESS_INTENT_NAMESPACE,
                tenantId, workflowInstanceId);
    }

    /**
     * The reverse-event publication identity is derived from its immutable
     * business intent, never allocated at transaction retry time.  Both the
     * broker start path and the human approval path use this rule.
     */
    public static UUID outboxId(UUID tenantId, String contractRef, byte[] businessIntentKey) {
        return WorkflowIdempotencyCodec.uuidFromDigest(WorkflowIdempotencyCodec.derivedKey(
                "FD-WORKFLOW-REVERSE-OUTBOX-V1", tenantId, contractRef, businessIntentKey));
    }

    public static UUID messageId(UUID tenantId, String contractRef, byte[] businessIntentKey) {
        return WorkflowIdempotencyCodec.uuidFromDigest(WorkflowIdempotencyCodec.derivedKey(
                "FD-WORKFLOW-REVERSE-MESSAGE-V1", tenantId, contractRef, businessIntentKey));
    }

    private static String format(Instant value) {
        return MICROS.format(value.truncatedTo(ChronoUnit.MICROS));
    }

    private static byte[] checked(String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        if (body.length > WorkflowReverseEventContract.MAX_BODY_BYTES) {
            throw new IllegalArgumentException("M6 event payload exceeds 64 KiB");
        }
        return body;
    }
}
