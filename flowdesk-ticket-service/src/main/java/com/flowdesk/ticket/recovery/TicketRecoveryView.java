package com.flowdesk.ticket.recovery;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/** 对外展示的工单恢复状态，包含来源、重试与人工重放记录及当前并发版本。 */
public record TicketRecoveryView(
        UUID recoveryId, String owner, String direction, UUID sourceRecordId,
        String businessIntentDigest, String state, int attemptCount, Instant nextAttemptAt,
        String lastErrorCode, String lastErrorSummary, Instant deadLetteredAt,
        Instant recoveryRequiredAt, int replayCount, Instant lastReplayedAt,
        ReplayedBy lastReplayedBy, String lastReplayReason, Instant resolvedAt,
        long lockVersion, Instant createdAt, Instant updatedAt) {
    /**
     * 发起重放的主体快照，供恢复详情追溯人工操作身份。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReplayedBy(String actorType, UUID actorId) { }
}
