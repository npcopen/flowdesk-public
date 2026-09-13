package com.flowdesk.workflow.service;

import java.util.UUID;

/** Frozen digest for a restricted two-level Workflow version snapshot. */
public final class WorkflowRuleDigest {

    private WorkflowRuleDigest() {
    }

    public static byte[] digest(UUID tenantId, UUID definitionId, String fieldCode,
                                String operator, String valueType, String value,
                                UUID level1ApproverUserId, UUID level2ApproverUserId) {
        return WorkflowIdempotencyCodec.derivedKey("FD-WORKFLOW-VERSION-V1",
                tenantId, definitionId, fieldCode, operator, valueType, value,
                level1ApproverUserId, level2ApproverUserId);
    }
}
