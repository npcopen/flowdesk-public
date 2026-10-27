package com.flowdesk.workflow.model.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * 工作流持久化行快照，保存数据库查询得到的业务字段，供服务校验与响应映射使用。
 */
public record AuditRow(UUID auditId, String actionCode, String targetType,
                       UUID targetId, String outcome, String actorType, UUID actorId,
                       String reason, String errorCode, UUID correlationId,
                       UUID causationId, Instant occurredAt) {
}
