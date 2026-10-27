package com.flowdesk.alert.mapper;
import com.flowdesk.alert.model.LinkRow;

import com.flowdesk.alert.model.*;
import com.flowdesk.common.tenant.TenantContextHolder;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 事件与工单关联及告警发件箱持久化接口，保存建单意图和待发布消息。
 * 默认入口校验当前租户，跨表写入的事务由调用方组织。
 */
public interface AlertMessageMapper {
    /**
     * 校验当前租户后，为事件实例建立 REQUESTED 状态的工单关联，保存建单业务意图，初始化重试次数和版本。
     */
    default void insertTicketLink(UUID tenantId, UUID incidentId, byte[] intent, Instant now) {
        TenantContextHolder.current().requireTenant(tenantId);
        sqlInsertTicketLink(tenantId, incidentId, intent, now);
    }
    /**
     * 为事件实例建立 REQUESTED 状态的工单关联，保存建单业务意图，初始化重试次数和版本。
     */
    void sqlInsertTicketLink(
            @Param("tenantId") UUID tenantId,
            @Param("incidentId") UUID incidentId,
            @Param("intent") byte[] intent,
            @Param("now") Instant now);
    /**
     * 校验当前租户后，保存以事件实例为聚合根的版本 1 消息，包含发布键、业务意图、原文及摘要；初始为 PENDING，now 为首次可发送时间。
     */
    default void insertOutbox(UUID tenantId, UUID outboxId, UUID messageId, byte[] publicationKey, byte[] businessIntent, UUID incidentId, String contractRef, byte[] body, byte[] bodyHash, UUID correlationId, UUID causationId, Instant now, String creatorType) {
        TenantContextHolder.current().requireTenant(tenantId);
        sqlInsertOutbox(tenantId, outboxId, messageId, publicationKey, businessIntent, incidentId, contractRef, body, bodyHash, correlationId, causationId, now, creatorType);
    }
    /**
     * 保存以事件实例为聚合根的版本 1 消息，包含发布键、业务意图、原文及摘要；初始为 PENDING，now 为首次可发送时间。
     */
    void sqlInsertOutbox(
            @Param("tenantId") UUID tenantId,
            @Param("outboxId") UUID outboxId,
            @Param("messageId") UUID messageId,
            @Param("publicationKey") byte[] publicationKey,
            @Param("businessIntent") byte[] businessIntent,
            @Param("incidentId") UUID incidentId,
            @Param("contractRef") String contractRef,
            @Param("body") byte[] body,
            @Param("bodyHash") byte[] bodyHash,
            @Param("correlationId") UUID correlationId,
            @Param("causationId") UUID causationId,
            @Param("now") Instant now,
            @Param("creatorType") String creatorType);
    /**
     * 校验当前租户后，按租户和事件实例读取工单关联及重试状态；lock 为 true 时追加 FOR UPDATE，供同一事务内更新关联。
     *
     * @return 查询结果，未命中时为空 Optional。
     */
    default Optional<LinkRow> findLink(UUID tenantId, UUID incidentId, boolean lock) {
        TenantContextHolder.current().requireTenant(tenantId);
        return sqlFindLink(tenantId, incidentId, lock);
    }
    /**
     * 按租户和事件实例读取工单关联及重试状态；lock 为 true 时追加 FOR UPDATE，供同一事务内更新关联。
     *
     * @return 查询结果，未命中时为空 Optional。
     */
    Optional<LinkRow> sqlFindLink(@Param("tenantId") UUID tenantId, @Param("incidentId") UUID incidentId, @Param("lock") boolean lock);
}
