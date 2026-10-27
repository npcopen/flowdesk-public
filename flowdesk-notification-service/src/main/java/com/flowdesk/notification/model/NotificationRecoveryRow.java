package com.flowdesk.notification.model;
import java.util.*;
import java.time.Instant;

/**
 * 通知持久化行快照，保存数据库查询得到的业务字段，供服务校验与响应映射使用。
 */
public record NotificationRecoveryRow(UUID recoveryId, String direction, UUID sourceRecordId,
                              byte[] businessIntentKey, String state, int attemptCount,
                              Instant nextAttemptAt, String lastErrorCode, String lastErrorSummary,
                              Instant deadLetteredAt, Instant recoveryRequiredAt, int replayCount,
                              Instant lastReplayedAt, String lastReplayedByType,
                              UUID lastReplayedById, String lastReplayReason,
                              UUID lastReplayCorrelationId, Instant resolvedAt, long lockVersion,
                              Instant createdAt, Instant updatedAt) {
        /**
         * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
         */
        public NotificationRecoveryRow { businessIntentKey = businessIntentKey == null ? null : businessIntentKey.clone(); }
        /**
         * 返回业务意图键的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override public byte[] businessIntentKey() { return businessIntentKey == null ? null : businessIntentKey.clone(); }
    }
