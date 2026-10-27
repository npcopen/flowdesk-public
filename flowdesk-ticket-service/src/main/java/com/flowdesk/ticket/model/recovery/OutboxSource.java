package com.flowdesk.ticket.model.recovery;

import java.util.UUID;

/**
 * 恢复项关联 Outbox 原始事实，供重新排队原消息。
 */
public record OutboxSource(UUID outboxId, String contractRef, int contractVersion, UUID ticketId,
                    UUID correlationId, String status, long lockVersion,
                    String linkStatus, long linkLockVersion) { }
