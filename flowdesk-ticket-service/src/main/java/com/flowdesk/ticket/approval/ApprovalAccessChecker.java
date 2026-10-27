package com.flowdesk.ticket.approval;

import java.util.UUID;

/** 审批申请摘要的访问校验端口：必须确认当前用户确实拥有指定工单对应的审批任务。 */
public interface ApprovalAccessChecker {
    /**
     * 核验认证用户是否能读取指定审批上下文，避免关联查询扩大工单访问权限。
     */
    boolean canRead(UUID tenantId, UUID actorId, UUID taskId, UUID ticketId, String authorization);
}
