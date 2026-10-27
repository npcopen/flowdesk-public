package com.flowdesk.ticket.model.incident.result;

import java.util.UUID;

/**
 * 结果发布审计状态快照，供恢复结算内容核验。
 */
public record AuditState(String action, String targetType, UUID targetId, String outcome,
                          String actorType, UUID actorId, String reason, String errorCode,
                          UUID correlationId, UUID causationId) { }
