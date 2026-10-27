package com.flowdesk.ticket.model.reverse;

import java.util.UUID;

/**
 * 消息处理与恢复审计快照，供重放一致性及详情展示。
 */
public record Audit(UUID auditId, String actionCode, String targetType, UUID targetId,
                    String outcome, String actorType, UUID actorId, String reason,
                    String errorCode, UUID correlationId, UUID causationId) { }
