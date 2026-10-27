package com.flowdesk.workflow.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * 工作流恢复记录的 API 投影，提供状态和错误摘要而不暴露原始载荷。
 */
public record WorkflowRecoveryView(
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

    /**
     * 恢复视图中的最近重放操作者信息，供界面展示及审计追溯。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReplayedBy(String actorType, UUID actorId) {
    }
}
