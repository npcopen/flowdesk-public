package com.flowdesk.ticket.iam;

import java.util.UUID;

/** 分配、转派工单前调用 IAM API-016 的资格校验端口，确认目标是同租户有效 Agent。 */
public interface AssigneeEligibilityChecker {

    /** @return 用户属于指定租户且是有效 ACTIVE AGENT 时返回 true */
    boolean isEligibleAgent(UUID tenantId, UUID userId);
}
