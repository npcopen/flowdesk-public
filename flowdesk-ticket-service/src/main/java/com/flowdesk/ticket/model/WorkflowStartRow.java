package com.flowdesk.ticket.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 工单工作流启动关联快照，供异步状态与恢复查询。
 */
public record WorkflowStartRow(UUID ticketId, String status, UUID workflowInstanceId,
                               Instant requestedAt, Instant establishedAt,
                               Instant recoveryRequiredAt, String recoveryReasonCode,
                               int retryCount, Instant lastAttemptAt, long lockVersion,
                               Instant createdAt, Instant updatedAt) {
}
