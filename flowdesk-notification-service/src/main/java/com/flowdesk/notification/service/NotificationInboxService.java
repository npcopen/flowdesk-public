package com.flowdesk.notification.service;
import com.flowdesk.notification.model.*;
import java.time.Instant;
import java.util.*;
import com.flowdesk.notification.event.*;
/**
 * 通知 Inbox 去重和状态写入契约，统一识别消息、意图及发布身份冲突，并保留恢复原始事实。
 */
public interface NotificationInboxService {
    /**
     * 按声明的租户与业务条件查询消息重复事实，供调用方核对可见性、状态或幂等关系。
     */
    NotificationInboxDuplicate findDuplicate(AcceptedNotification message, boolean lock);
    /**
     * 按声明的租户与业务条件查询消息重复事实，供调用方核对可见性、状态或幂等关系。
     */
    NotificationInboxDuplicate findDuplicate(NotificationEnvelope message, boolean lock);
    /**
     * 持久化处理成功 Inbox，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    void insertProcessedInbox(AcceptedNotification message, UUID inboxId,
                                     UUID resultId, Instant now);
    /**
     * 持久化确定性拒绝 Inbox，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    void insertRejectedInbox(AcceptedNotification message, UUID inboxId,
                                     String code, Instant now);
    /**
     * 持久化确定性拒绝 Inbox，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    void insertRejectedInbox(NotificationEnvelope message, UUID inboxId,
                                    String code, Instant now);
    /**
     * 持久化待恢复 Inbox，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    void insertRecoveryInbox(AcceptedNotification message, UUID inboxId,
                                     String code, Instant now);
    /**
     * 按原 Inbox 身份结算恢复结果，避免重新创建重复接纳事实。
     */
    int completeRecoveredInbox(AcceptedNotification message, NotificationInboxRow row,
                                      UUID resultId, Instant now);
    /**
     * 按原 Inbox 身份结算恢复结果，避免重新创建重复接纳事实。
     */
    int completeRecoveredInbox(NotificationEnvelope message, NotificationInboxRow row,
                                      UUID resultId, Instant now);
    /**
     * 将待恢复 Inbox 更新为拒绝结果，保留原消息身份和内容。
     */
    int rejectRecoveredInbox(AcceptedNotification message, NotificationInboxRow row,
                                    String code, Instant now);
    /**
     * 将待恢复 Inbox 更新为拒绝结果，保留原消息身份和内容。
     */
    int rejectRecoveredInbox(NotificationEnvelope message, NotificationInboxRow row,
                                    String code, Instant now);
    /**
     * 按声明的租户与业务条件查询Inbox，供调用方核对可见性、状态或幂等关系。
     */
    Optional<NotificationInboxRow> findInbox(UUID tenantId, UUID inboxId, boolean lock);
    /**
     * 按租户和发布身份查找 Inbox，区分原消息重投与同一发布下的内容冲突。
     */
    Optional<NotificationInboxRow> findInboxByPublication(UUID tenantId, byte[] publicationKey,
                                                     boolean lock);
}
