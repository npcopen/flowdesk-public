package com.flowdesk.notification.mapper;

import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.notification.model.NotificationRow;
import org.apache.ibatis.annotations.Param;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 用户通知持久化接口，支持通知去重、个人列表、未读数量及阅读状态更新。
 * 默认入口校验租户，写入要求已有事务；阅读更新还以收件人及版本约束目标。
 */
public interface NotificationMapper {
    /**
     * 校验当前租户后读取单条通知，将底层可空结果包装为 Optional；lock 控制事务内行锁。
     *
     * @return 查询结果，未命中时为空 Optional。
     */
    default Optional<NotificationRow> findNotification(UUID tenantId, UUID notificationId, boolean lock) {
        requireTenant(tenantId); return Optional.ofNullable(selectNotification(tenantId, notificationId, lock));
    }
    /**
     * 按租户及通知标识读取单条通知；lock 为 true 时使用 FOR UPDATE，未命中时底层返回 null。
     *
     * @return 匹配的通知，未命中时为 null。
     */
    NotificationRow selectNotification(
            @Param("tenantId") UUID tenantId,
            @Param("notificationId") UUID notificationId,
            @Param("lock") boolean lock);
    /**
     * 校验当前租户后按收件人、来源服务、效果键及类型查询既有通知，以 Optional 表达未命中，供业务效果去重。
     *
     * @return 查询结果，未命中时为空 Optional。
     */
    default Optional<NotificationRow> findByEffect(UUID tenantId, UUID recipientId, String sourceService, byte[] sourceEffect, String kind, boolean lock) {
        requireTenant(tenantId); return Optional.ofNullable(selectByEffect(tenantId, recipientId, sourceService, sourceEffect, kind, lock));
    }
    /**
     * 按租户、收件人、来源服务、效果键及通知类型查询既有通知，供业务效果去重；lock 控制行锁。
     *
     * @return 匹配的通知，未命中时为 null。
     */
    NotificationRow selectByEffect(
            @Param("tenantId") UUID tenantId,
            @Param("recipientId") UUID recipientId,
            @Param("sourceService") String sourceService,
            @Param("sourceEffect") byte[] sourceEffect,
            @Param("kind") String kind,
            @Param("lock") boolean lock);
    /**
     * 校验租户并要求人工用户主体，仅查询该用户收到的通知；将 limit 限制在 1 至 101，以创建时间及通知标识分页。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    default List<NotificationRow> listNotifications(UUID tenantId, String state, String kind, Instant from, Instant to, boolean ascending, Instant cursorValue, UUID cursorId, int limit) {
        requireTenant(tenantId);
        var actor = TenantContextHolder.current().actor();
        if (!(actor instanceof com.flowdesk.common.tenant.ActorRef.Human human)) throw new IllegalStateException("Notification list requires a human actor");
        return selectNotifications(tenantId, human.userId(), state, kind, from, to, ascending, cursorValue, cursorId, Math.max(1, Math.min(101, limit)));
    }
    /**
     * 限定租户及收件人，按状态、类型及创建时间筛选，以创建时间和通知标识进行稳定游标分页，最多 limit 条。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<NotificationRow> selectNotifications(
            @Param("tenantId") UUID tenantId,
            @Param("recipientId") UUID recipientId,
            @Param("state") String state,
            @Param("kind") String kind,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("ascending") boolean ascending,
            @Param("cursorValue") Instant cursorValue,
            @Param("cursorId") UUID cursorId,
            @Param("limit") int limit);
    /**
     * 校验当前租户后统计指定收件人的未读通知数量。
     *
     * @return 符合条件的记录数量。
     */
    default long countUnread(UUID tenantId, UUID recipientId) {
        requireTenant(tenantId);
        return selectUnreadCount(tenantId,recipientId);
    }
    /**
     * 统计指定租户及收件人状态为 UNREAD 的通知数量。
     *
     * @return 符合条件的记录数量。
     */
    long selectUnreadCount(@Param("tenantId") UUID tenantId, @Param("recipientId") UUID recipientId);
    /**
     * 校验当前租户及实际事务后新增未读通知；来源效果、收件人和类型用于持久化唯一性约束识别重复业务效果。
     */
    default void insertNotification(UUID tenantId, UUID notificationId, UUID recipientId, String kind, String sourceService, byte[] sourceEffect, String sourceRefType, UUID sourceRefId, String title, String body, Instant now) {
        requireTenant(tenantId); requireTransaction(); insertNotificationRow(tenantId,notificationId,recipientId,kind,sourceService,sourceEffect,sourceRefType,sourceRefId,title,body,now);
    }
    /**
     * 保存来源效果及通知内容，初始化为 UNREAD、已读时间为空、版本为 0，以服务主体记录创建信息。
     */
    void insertNotificationRow(
            @Param("tenantId") UUID tenantId,
            @Param("notificationId") UUID notificationId,
            @Param("recipientId") UUID recipientId,
            @Param("kind") String kind,
            @Param("sourceService") String sourceService,
            @Param("sourceEffect") byte[] sourceEffect,
            @Param("sourceRefType") String sourceRefType,
            @Param("sourceRefId") UUID sourceRefId,
            @Param("title") String title,
            @Param("body") String body,
            @Param("now") Instant now);
    /**
     * 校验当前租户及实际事务后更新指定收件人的阅读状态，以 expectedVersion 保护并发更新。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    default int updateReadState(UUID tenantId, UUID notificationId, UUID recipientId, long expectedVersion, String state, Instant readAt, Instant now) {
        requireTenant(tenantId); requireTransaction(); return updateReadStateRow(tenantId,notificationId,recipientId,expectedVersion,state,readAt,now);
    }
    /**
     * 以租户、通知、收件人及 expectedVersion 更新阅读状态和 readAt，增加版本并将收件人记为更新主体。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int updateReadStateRow(
            @Param("tenantId") UUID tenantId,
            @Param("notificationId") UUID notificationId,
            @Param("recipientId") UUID recipientId,
            @Param("expectedVersion") long expectedVersion,
            @Param("state") String state,
            @Param("readAt") Instant readAt,
            @Param("now") Instant now);
    /**
     * 校验 tenantId 与当前可信租户上下文一致，避免默认入口跨租户访问。
     */
    private static void requireTenant(UUID tenantId) { TenantContextHolder.current().requireTenant(tenantId); }
    /**
     * 校验当前存在实际事务，防止通知写入脱离调用方组织的业务事务。
     */
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Notification mutation requires a transaction");
    }
}
