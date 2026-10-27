package com.flowdesk.ticket.model.outbox;

import java.util.UUID;

/**
 * 后台待处理候选快照，保留领取所需身份、时间与版本。
 */
public record Candidate(UUID tenantId, UUID outboxId, String status, int attemptCount,
                        String lockedBy, UUID correlationId, long lockVersion) {
}
