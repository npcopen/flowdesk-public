package com.flowdesk.alert.mapper;
import com.flowdesk.alert.model.EventRow;

import com.flowdesk.alert.model.*;
import com.flowdesk.common.tenant.TenantContextHolder;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Webhook 防重放凭据及告警原始事件持久化接口，支持去重、周期匹配和事件查询。
 * PreTrust 入口在可信租户上下文建立前使用显式租户参数；普通入口校验当前租户。
 */
public interface AlertEventMapper {
    /**
     * 可信租户上下文建立前，使用显式 tenantId 保存集成的 Webhook nonce 摘要、请求时间及过期时间，供防重放唯一性约束识别重复请求。
     */
    default void insertNoncePreTrust(UUID tenantId, UUID nonceId, UUID integrationId, byte[] nonceHash, Instant requestTimestamp, Instant acceptedAt, Instant expiresAt) {
        sqlInsertNoncePreTrust(tenantId, nonceId, integrationId, nonceHash, requestTimestamp, acceptedAt, expiresAt);
    }
    /**
     * 保存集成的 Webhook nonce 摘要、请求时间及过期时间，供防重放唯一性约束识别重复请求。
     */
    void sqlInsertNoncePreTrust(
            @Param("tenantId") UUID tenantId,
            @Param("nonceId") UUID nonceId,
            @Param("integrationId") UUID integrationId,
            @Param("nonceHash") byte[] nonceHash,
            @Param("requestTimestamp") Instant requestTimestamp,
            @Param("acceptedAt") Instant acceptedAt,
            @Param("expiresAt") Instant expiresAt);
    /**
     * 可信租户上下文建立前，使用显式 tenantId 按租户、集成和外部事件标识查询既有事件，用于校验重复投递，并同时返回集成展示信息。
     *
     * @return 查询结果，未命中时为空 Optional。
     */
    default Optional<EventRow> findEventPreTrust(UUID tenantId, UUID integrationId, String externalEventId) {
        return sqlFindEventPreTrust(tenantId, integrationId, externalEventId);
    }
    /**
     * 按租户、集成和外部事件标识查询既有事件，用于校验重复投递，并同时返回集成展示信息。
     *
     * @return 查询结果，未命中时为空 Optional。
     */
    Optional<EventRow> sqlFindEventPreTrust(
            @Param("tenantId") UUID tenantId,
            @Param("integrationId") UUID integrationId,
            @Param("externalEventId") String externalEventId);
    /**
     * 可信租户上下文建立前，使用显式 tenantId 查询同租户、指纹及周期内未关联实例的 RESOLVED 事件，按接收时间和事件标识返回最早一条。
     *
     * @return 查询结果，未命中时为空 Optional。
     */
    default Optional<EventRow> findUnlinkedRecoveryPreTrust(UUID tenantId, String fingerprint, String cycleRef) {
        return sqlFindUnlinkedRecoveryPreTrust(tenantId, fingerprint, cycleRef);
    }
    /**
     * 查询同租户、指纹及周期内未关联实例的 RESOLVED 事件，按接收时间和事件标识返回最早一条。
     *
     * @return 查询结果，未命中时为空 Optional。
     */
    Optional<EventRow> sqlFindUnlinkedRecoveryPreTrust(
            @Param("tenantId") UUID tenantId,
            @Param("fingerprint") String fingerprint,
            @Param("cycleRef") String cycleRef);
    /**
     * 校验当前租户后，查询同指纹在 recoveredAt 后发生、未关联且对应周期尚未建立实例的 FIRING 事件，按发生时间和事件标识排序。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    default List<EventRow> findWaitingCycles(UUID tenantId, String fingerprint, Instant recoveredAt) {
        TenantContextHolder.current().requireTenant(tenantId);
        return sqlFindWaitingCycles(tenantId, fingerprint, recoveredAt);
    }
    /**
     * 查询同指纹在 recoveredAt 后发生、未关联且对应周期尚未建立实例的 FIRING 事件，按发生时间和事件标识排序。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<EventRow> sqlFindWaitingCycles(
            @Param("tenantId") UUID tenantId,
            @Param("fingerprint") String fingerprint,
            @Param("recoveredAt") Instant recoveredAt);
    /**
     * 可信租户上下文建立前，使用显式 tenantId 写入事件外部标识、周期、处置结果及原文摘要；原文引用和密文列为空，创建主体记录为监控集成。
     */
    default void insertEventPreTrust(UUID tenantId, UUID eventId, UUID integrationId, String externalId, String eventType, String fingerprint, String cycleRef, Instant occurredAt, Instant receivedAt, String disposition, UUID incidentId, byte[] rawHash, String summary, UUID correlationId) {
        sqlInsertEventPreTrust(tenantId, eventId, integrationId, externalId, eventType, fingerprint, cycleRef, occurredAt, receivedAt, disposition, incidentId, rawHash, summary, correlationId);
    }
    /**
     * 写入事件外部标识、周期、处置结果及原文摘要；原文引用和密文列为空，创建主体记录为监控集成。
     */
    void sqlInsertEventPreTrust(
            @Param("tenantId") UUID tenantId,
            @Param("eventId") UUID eventId,
            @Param("integrationId") UUID integrationId,
            @Param("externalId") String externalId,
            @Param("eventType") String eventType,
            @Param("fingerprint") String fingerprint,
            @Param("cycleRef") String cycleRef,
            @Param("occurredAt") Instant occurredAt,
            @Param("receivedAt") Instant receivedAt,
            @Param("disposition") String disposition,
            @Param("incidentId") UUID incidentId,
            @Param("rawHash") byte[] rawHash,
            @Param("summary") String summary,
            @Param("correlationId") UUID correlationId);
    /**
     * 校验当前租户后，按租户和事件标识读取告警详情，并关联监控集成编码及名称。
     *
     * @return 查询结果，未命中时为空 Optional。
     */
    default Optional<EventRow> findEvent(UUID tenantId, UUID eventId) {
        TenantContextHolder.current().requireTenant(tenantId);
        return sqlFindEvent(tenantId, eventId);
    }
    /**
     * 按租户和事件标识读取告警详情，并关联监控集成编码及名称。
     *
     * @return 查询结果，未命中时为空 Optional。
     */
    Optional<EventRow> sqlFindEvent(@Param("tenantId") UUID tenantId, @Param("eventId") UUID eventId);
    /**
     * 校验当前租户后，按类型、处置、周期、实例及时间等条件筛选，结合已建立周期推导展示处置状态；以时间与事件标识进行稳定游标分页并限制条数。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    default List<EventRow> listEvents(UUID tenantId, EventFilter filter, String sort, Instant cursorTime, UUID cursorId, int limit) {
        TenantContextHolder.current().requireTenant(tenantId);
        return sqlListEvents(tenantId, filter, sort, cursorTime, cursorId, limit);
    }
    /**
     * 按类型、处置、周期、实例及时间等条件筛选，结合已建立周期推导展示处置状态；以时间与事件标识进行稳定游标分页并限制条数。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<EventRow> sqlListEvents(
            @Param("tenantId") UUID tenantId,
            @Param("filter") EventFilter filter,
            @Param("sort") String sort,
            @Param("cursorTime") Instant cursorTime,
            @Param("cursorId") UUID cursorId,
            @Param("limit") int limit);
}
