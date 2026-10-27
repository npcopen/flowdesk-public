package com.flowdesk.ticket.approval;

import java.time.Instant;
import java.util.UUID;
/**
 * 审批侧读取申请摘要的业务契约，仅允许有对应审批任务访问权的用户查看必要工单信息。
 */
public interface ApprovalContextService {
    /**
     * 读取指定审批上下文详情，并依据当前租户与权限校验资源可见性。
     */
    ApplicationSummary get(UUID ticketId, UUID taskId, String authorization);
    /**
     * 审批页面使用的申请摘要，仅包含审阅事项所需的工单、目录和申请人信息。
     */
    record ApplicationSummary(UUID ticketId, String ticketNo, UUID requesterUserId,
        String subject, String description, String status, String catalogName, Instant createdAt) { }
}
