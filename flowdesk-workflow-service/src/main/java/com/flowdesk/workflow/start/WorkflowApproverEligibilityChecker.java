package com.flowdesk.workflow.start;

import java.util.UUID;

/** Port for the transaction-external IAM API-016 Workflow approver pre-check. */
@FunctionalInterface
public interface WorkflowApproverEligibilityChecker {

    boolean isEligibleApprover(UUID tenantId, UUID approverUserId);
}
