package com.flowdesk.notification.recovery;

import com.flowdesk.common.recovery.RecoveryReplayResumer;
import com.flowdesk.notification.dto.NotificationDtos;
import java.util.UUID;
import static com.flowdesk.notification.recovery.NotificationRecoveryResponseMapper.response;

/** 恢复用例接口。 */
public interface NotificationRecoveryReplayService {
    /**
     * 按人工命令领取重放执行权，经版本与幂等保护后返回恢复执行结果。
     */
    NotificationDtos.MessageRecoveryResponse replay(UUID recoveryId, NotificationDtos.ReplayCommand command, String ifMatch, String idempotencyKey);
    /**
     * 继续执行已持久化的重放任务，供重启后补偿尚未完成的恢复尝试。
     */
    void resume(RecoveryReplayResumer.Task task);
}
