package com.flowdesk.ticket.recovery;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

public record TicketRecoveryView(
        UUID recoveryId, String owner, String direction, UUID sourceRecordId,
        String businessIntentDigest, String state, int attemptCount, Instant nextAttemptAt,
        String lastErrorCode, String lastErrorSummary, Instant deadLetteredAt,
        Instant recoveryRequiredAt, int replayCount, Instant lastReplayedAt,
        ReplayedBy lastReplayedBy, String lastReplayReason, Instant resolvedAt,
        long lockVersion, Instant createdAt, Instant updatedAt) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReplayedBy(String actorType, UUID actorId) { }
}
