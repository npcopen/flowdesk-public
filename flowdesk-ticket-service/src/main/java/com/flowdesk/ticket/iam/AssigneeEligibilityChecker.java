package com.flowdesk.ticket.iam;

import java.util.UUID;

/** Port for the IAM API-016 eligibility pre-check used by assign/transfer. */
public interface AssigneeEligibilityChecker {

    /** @return true when the user is an eligible ACTIVE AGENT in the given tenant */
    boolean isEligibleAgent(UUID tenantId, UUID userId);
}
