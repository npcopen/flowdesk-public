package com.flowdesk.notification.dto;

import com.flowdesk.common.validation.NoControlCharacters;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** 通知与受保护恢复接口的请求、响应数据结构，包含状态版本和人工重放原因约束。 */
public final class NotificationDtos {
    /**
     * 禁止直接实例化工具容器，相关静态规则与转换由调用方按业务需要复用。
     */
    private NotificationDtos() {
    }

    /**
     * 通知对外响应快照，固定暴露当前接口允许展示的状态与关联信息。
     */
    public record NotificationResponse(UUID notificationId, String notificationKind,
                                       String sourceDomain, String sourceRefType,
                                       UUID sourceRefId, String title, String body,
                                       String state, Instant readAt, Instant createdAt,
                                       Instant updatedAt, long lockVersion) {
    }

    /**
     * 通知接口输入模型，声明调用方可提交的字段与校验约束，供对应命令入口解析。
     */
    public record ReadStateWrite(@NotNull State state) {
        /**
         * 通知内部协作类型，组合当前处理所需的结构与行为，供所在用例的依赖链调用。
         */
        public enum State { READ, UNREAD }
    }

    /**
     * 通知对外响应快照，固定暴露当前接口允许展示的状态与关联信息。
     */
    public record UnreadCountResponse(long unreadCount, Instant asOf) {
    }

    /**
     * 通知对外响应快照，固定暴露当前接口允许展示的状态与关联信息。
     */
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

    /**
     * 恢复操作的持久化主体信息，供异步执行与重启续跑保留最初操作者身份。
     */
    public record ReplayActor(String actorType, UUID actorId) {
    }

    /**
     * 通知接口输入模型，声明调用方可提交的字段与校验约束，供对应命令入口解析。
     */
    public record ReplayCommand(
            @Size(min = 1, max = 1000) @NoControlCharacters String reason) {
    }
}
