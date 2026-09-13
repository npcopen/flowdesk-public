package com.flowdesk.ticket.event;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Canonical JSON encoder for the payload persisted before
 * {@code flowdesk.workflow.start.v1} publication.
 */
public final class WorkflowStartV1PayloadCodec {

    private static final ObjectMapper JSON = new ObjectMapper();

    private WorkflowStartV1PayloadCodec() {
    }

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
