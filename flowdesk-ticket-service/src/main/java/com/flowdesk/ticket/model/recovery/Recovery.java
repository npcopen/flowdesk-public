package com.flowdesk.ticket.model.recovery;

import java.time.Instant;
import java.util.UUID;

/**
 * 恢复记录快照，包含来源、错误、处理状态与重放租约。
 */
public record Recovery(UUID recoveryId, String direction, UUID sourceRecordId,
                byte[] businessIntentKey, String state, int attemptCount,
                Instant nextAttemptAt, String lastErrorCode, String lastErrorSummary,
                Instant deadLetteredAt, Instant recoveryRequiredAt, int replayCount,
                Instant lastReplayedAt, String lastReplayedByType, UUID lastReplayedById,
                String lastReplayReason, UUID lastReplayCorrelationId, Instant resolvedAt,
                long lockVersion, Instant createdAt, Instant updatedAt) { }
