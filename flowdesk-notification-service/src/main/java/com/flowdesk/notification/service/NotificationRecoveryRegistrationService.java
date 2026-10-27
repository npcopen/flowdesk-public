package com.flowdesk.notification.service;
import java.util.UUID;
import java.time.Instant;
/**
 * 通知恢复事实登记边界，使运行时处理通过受控入口创建待人工处理记录。
 */
public interface NotificationRecoveryRegistrationService {
    /**
     * 持久化恢复记录，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    void insertRecovery(UUID tenantId,UUID recoveryId,UUID inboxId,byte[] businessIntent,String errorCode,String summary,Instant now);
}
