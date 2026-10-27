package com.flowdesk.alert.recovery;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * 告警恢复记录的对外快照，供恢复列表、详情和重放接纳响应共用，不包含原始消息正文。
 *
 * <p>attemptCount 是消息处理尝试次数，replayCount 是人工接纳次数；二者不能混为同一重试计数。
 * Inbox 处于 REPLAYING 时，nextAttemptAt 表示执行租约截止时间；lockVersion 对应响应 ETag。
 * lastReplayedBy 和原因保留最近一次人工操作，最终结果通过 state 与 resolvedAt 判断。
 */
public record AlertRecoveryView(
        UUID recoveryId,
        String owner,
        String direction,
        UUID sourceRecordId,
        String businessIntentDigest,
        String state,
        int attemptCount,
        Instant nextAttemptAt,
        String lastErrorCode,
        String lastErrorSummary,
        Instant deadLetteredAt,
        Instant recoveryRequiredAt,
        int replayCount,
        Instant lastReplayedAt,
        ReplayedBy lastReplayedBy,
        String lastReplayReason,
        Instant resolvedAt,
        long lockVersion,
        Instant createdAt,
        Instant updatedAt) {
    /** 操作者取自恢复记录保存的 last_replayed_by_* 字段；尚未发生人工重放时，外层值可以为空。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReplayedBy(String actorType, UUID actorId) {}
}
