package com.flowdesk.ticket.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 人工工单审计快照，保存动作、目标、主体与请求摘要。
 */
public record AuditRow(UUID auditId, String actionCode, String targetType,
                       UUID targetId, String outcome, String actorType, UUID actorId,
                       String reason, String errorCode, UUID correlationId,
                       UUID causationId, Instant occurredAt, Instant recordedAt) {
}
