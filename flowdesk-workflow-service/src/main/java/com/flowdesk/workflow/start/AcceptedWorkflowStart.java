package com.flowdesk.workflow.start;

import java.time.Instant;
import java.util.UUID;

/** Immutable output of the pre-tenant transport guard. */
public record AcceptedWorkflowStart(
        UUID tenantId,
        String producerService,
        UUID messageId,
        byte[] businessIntentKey,
        UUID ticketId,
        UUID workflowDefinitionId,
        UUID workflowVersionId,
        int versionNo,
        String serviceRiskLevel,
        UUID requesterUserId,
        UUID correlationId,
        UUID causationId,
        Instant occurredAt,
        String contractRef,
        int contractVersion,
        byte[] bodyBytes,
        byte[] bodySha256) {

    public AcceptedWorkflowStart {
        businessIntentKey = businessIntentKey.clone();
        bodyBytes = bodyBytes.clone();
        bodySha256 = bodySha256.clone();
    }

    @Override
    public byte[] businessIntentKey() {
        return businessIntentKey.clone();
    }

    @Override
    public byte[] bodyBytes() {
        return bodyBytes.clone();
    }

    @Override
    public byte[] bodySha256() {
        return bodySha256.clone();
    }
}
