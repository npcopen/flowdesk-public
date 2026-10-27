package com.flowdesk.notification.service;
import java.time.Instant;
import java.util.UUID;
/**
 * 通知审计幂等写入契约，唯一键命中后核对内容一致，防止冲突记录被当作成功重放。
 */
public interface NotificationAuditWriter {
    /**
     * 按稳定审计身份写入一次事实；命中唯一键后须核对内容一致，不能掩盖真实冲突。
     */
    void insertOnce(UUID tenantId, UUID auditId, String actionCode, String targetType,
                    UUID targetId, String outcome, String actorType, UUID actorId, String reason,
                    String errorCode, UUID correlationId, UUID causationId, Instant occurredAt);
}
