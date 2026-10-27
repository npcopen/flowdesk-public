package com.flowdesk.workflow.start;

import java.util.UUID;

/**
 * 事务外调用 IAM API-016 的审批人资格预检端口。
 */
@FunctionalInterface
public interface WorkflowApproverEligibilityChecker {

    /**
     * 查询目标用户的租户内审批资格，调用路径区分不合格与暂时依赖故障。
     */
    boolean isEligibleApprover(UUID tenantId, UUID approverUserId);
}
