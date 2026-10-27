package com.flowdesk.ticket.mapper;

import com.flowdesk.ticket.model.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.UUID;
/**
 * 工单事务消息写入接口，保存待发布消息并查询通知发布记录。
 * 具体 SQL 由 MyBatis 提供；更新操作遵循对应语句的状态和版本条件。
 */
@Mapper
public interface TicketOutboxWriteMapper {
    /**
     * 简化事务消息写入入口，因果 ID 留空，其余参数交由完整写入方法保存。
     *
     * @param tenantId 数据所属租户标识
     */
    default void insertOutboxEvent(UUID tenantId, UUID outboxId, UUID messageId, byte[] publicationKey, byte[] businessIntentKey, String aggregateType, UUID aggregateId, String contractRef, int contractVersion, byte[] bodyBytes, byte[] bodySha256, UUID correlationId, Instant createdAt) {
        insertOutboxEvent(tenantId, outboxId, messageId, publicationKey, businessIntentKey,
                aggregateType, aggregateId, contractRef, contractVersion, bodyBytes, bodySha256,
                correlationId, null, createdAt);
    }

    /**
     * 保存待发布的事务消息，保留消息体及摘要、发布键、业务意图键和链路信息，初始状态为 PENDING。
     *
     * @param tenantId 数据所属租户标识
     */
    void insertOutboxEvent(
            @Param("tenantId") UUID tenantId,
            @Param("outboxId") UUID outboxId,
            @Param("messageId") UUID messageId,
            @Param("publicationKey") byte[] publicationKey,
            @Param("businessIntentKey") byte[] businessIntentKey,
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") UUID aggregateId,
            @Param("contractRef") String contractRef,
            @Param("contractVersion") int contractVersion,
            @Param("bodyBytes") byte[] bodyBytes,
            @Param("bodySha256") byte[] bodySha256,
            @Param("correlationId") UUID correlationId,
            @Param("causationId") UUID causationId,
            @Param("createdAt") Instant createdAt);

    /**
     * 先校验当前租户上下文，再判断指定发布键和通知来源是否已有通知 Outbox 记录。
     *
     * @param tenantId 数据所属租户标识
     * @return 是否存在符合条件的记录
     */
    default boolean hasNotificationPublication(UUID tenantId, byte[] publicationKey, UUID sourceRefId) {
        com.flowdesk.common.tenant.TenantContextHolder.current().requireTenant(tenantId);
        return countNotificationPublications(tenantId, publicationKey, sourceRefId) > 0;
    }
    /**
     * 统计租户内发布键和来源 ID 匹配的 NOTIFICATION_REQUEST 记录，用于通知发布幂等判断。
     *
     * @param tenantId 数据所属租户标识
     * @return 符合条件的记录数量
     */
    long countNotificationPublications(
            @Param("tenantId") UUID tenantId,
            @Param("publicationKey") byte[] publicationKey,
            @Param("sourceRefId") UUID sourceRefId);
}
