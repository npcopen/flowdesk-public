package com.flowdesk.ticket.reverse;

import java.time.Instant;
import java.util.UUID;

public record AcceptedWorkflowReverse(
        WorkflowReverseContract.Binding binding,
        UUID tenantId, UUID messageId, byte[] businessIntentKey,
        UUID workflowInstanceId, UUID ticketId, byte[] startIntentKey,
        UUID workflowDefinitionId, UUID workflowVersionId, int versionNo,
         String instanceStatus, String outcome, Boolean conditionResult, Instant businessOccurredAt,
        UUID correlationId, UUID causationId, Instant envelopeOccurredAt,
        byte[] bodyBytes, byte[] bodySha256) {
    public AcceptedWorkflowReverse {
        businessIntentKey = businessIntentKey.clone();
        startIntentKey = startIntentKey.clone();
        bodyBytes = bodyBytes.clone();
        bodySha256 = bodySha256.clone();
    }
    @Override public byte[] businessIntentKey() { return businessIntentKey.clone(); }
    @Override public byte[] startIntentKey() { return startIntentKey.clone(); }
    @Override public byte[] bodyBytes() { return bodyBytes.clone(); }
    @Override public byte[] bodySha256() { return bodySha256.clone(); }
    public String decisionReason() {
        var node = new tools.jackson.databind.ObjectMapper().readTree(bodyBytes).get("decisionReason");
        return node != null && node.isString() ? node.stringValue() : null;
    }
}
