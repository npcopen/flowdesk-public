package com.flowdesk.ticket.model.recovery;

import java.time.Instant;
import java.util.UUID;

/**
 * 恢复项关联 Inbox 原始事实，供沿原消费路径重放。
 */
public record InboxSource(UUID inboxId, UUID messageId, byte[] businessIntentKey,
                   String contractRef, int contractVersion, byte[] bodyBytes, byte[] bodySha256,
                   UUID correlationId, UUID causationId, Instant occurredAt,
                   String status, long lockVersion) { }
