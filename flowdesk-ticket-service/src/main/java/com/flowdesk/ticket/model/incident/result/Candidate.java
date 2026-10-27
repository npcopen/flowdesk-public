package com.flowdesk.ticket.model.incident.result;

import java.util.UUID;

/**
 * 后台待处理候选快照，保留领取所需身份、时间与版本。
 */
public record Candidate(UUID tenantId, UUID outboxId, String status, int attemptCount,
                        String lockedBy, UUID correlationId, long lockVersion) { }
