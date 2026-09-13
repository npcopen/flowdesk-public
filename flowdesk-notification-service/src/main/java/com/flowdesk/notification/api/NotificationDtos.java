package com.flowdesk.notification.api;

import com.flowdesk.common.validation.NoControlCharacters;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** HTTP representations for Notification and its protected recovery view. */
public final class NotificationDtos {
    private NotificationDtos() {
    }

    public record NotificationResponse(UUID notificationId, String notificationKind,
                                       String sourceDomain, String sourceRefType,
                                       UUID sourceRefId, String title, String body,
                                       String state, Instant readAt, Instant createdAt,
                                       Instant updatedAt, long lockVersion) {
    }

    public record ReadStateWrite(@NotNull State state) {
        public enum State { READ, UNREAD }
    }

    public record UnreadCountResponse(long unreadCount, Instant asOf) {
    }

    public record MessageRecoveryResponse(UUID recoveryId, String owner, String direction,
                                          UUID sourceRecordId, String businessIntentDigest,
                                          String state, int attemptCount,
                                          Instant nextAttemptAt, String lastErrorCode,
                                          String lastErrorSummary, Instant deadLetteredAt,
                                          Instant recoveryRequiredAt, int replayCount,
                                          Instant lastReplayedAt, ReplayActor lastReplayedBy,
                                          String lastReplayReason,
                                          UUID lastReplayCorrelationId, Instant resolvedAt,
                                          long lockVersion, Instant createdAt,
                                          Instant updatedAt) {
    }

    public record ReplayActor(String actorType, UUID actorId) {
    }

    public record ReplayCommand(
            @Size(min = 1, max = 1000) @NoControlCharacters String reason) {
    }
}
