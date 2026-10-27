package com.flowdesk.notification.recovery;
import com.flowdesk.notification.model.*;

import com.flowdesk.notification.dto.NotificationDtos;


import java.util.HexFormat;

/** 将恢复持久化事实转换为 API 视图，统一业务意图摘要和操作者字段。 */
public final class NotificationRecoveryResponseMapper {
    /**
     * 禁止直接实例化工具容器，当前类型通过静态方法提供共享规则和转换。
     */
    private NotificationRecoveryResponseMapper() { }

    /**
     * 将持久化事实映射为接口响应，仅输出当前契约定义的业务字段。
     */
    public static NotificationDtos.MessageRecoveryResponse response(NotificationRecoveryRow row) {
        return new NotificationDtos.MessageRecoveryResponse(row.recoveryId(), "NOTIFICATION", row.direction(),
                row.sourceRecordId(), HexFormat.of().formatHex(row.businessIntentKey()), row.state(),
                row.attemptCount(), row.nextAttemptAt(), row.lastErrorCode(), row.lastErrorSummary(),
                row.deadLetteredAt(), row.recoveryRequiredAt(), row.replayCount(), row.lastReplayedAt(),
                row.lastReplayedByType() == null ? null : new NotificationDtos.ReplayActor(
                        row.lastReplayedByType(), row.lastReplayedById()), row.lastReplayReason(),
                row.lastReplayCorrelationId(), row.resolvedAt(), row.lockVersion(), row.createdAt(), row.updatedAt());
    }

}
