package com.flowdesk.ticket.model.incident;

import java.util.UUID;

/**
 * 既有审计身份与内容摘要，供重复消息内容核验。
 */
public record AuditIdentity(String action, String targetType, UUID targetId, String outcome,
                             String actorType, UUID actorId, String reason, String error,
                             UUID correlationId, UUID causationId) { }
