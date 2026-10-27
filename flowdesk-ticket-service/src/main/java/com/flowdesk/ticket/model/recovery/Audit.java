package com.flowdesk.ticket.model.recovery;

import java.util.UUID;

/**
 * 消息处理与恢复审计快照，供重放一致性及详情展示。
 */
public record Audit(String action, UUID targetId, String outcome, String actorType, UUID actorId,
             String reason, String errorCode, UUID correlationId, UUID causationId) { }
