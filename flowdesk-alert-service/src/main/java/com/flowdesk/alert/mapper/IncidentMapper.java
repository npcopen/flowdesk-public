package com.flowdesk.alert.mapper;
import com.flowdesk.alert.model.IncidentCycleRow;

import com.flowdesk.alert.model.*;
import com.flowdesk.common.tenant.TenantContextHolder;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 告警事件聚合与周期实例持久化接口，维护活动状态、代次、计数和恢复时间。
 * PreTrust 查询显式限定租户，普通入口校验租户上下文，并通过行锁或版本协调并发。
 */
public interface IncidentMapper {
    /**
     * 可信租户上下文建立前，使用显式 tenantId 按租户及指纹读取 ACTIVE 实例和首事件周期，始终使用 FOR UPDATE 串行处理同一活动实例。
     *
     * @return 查询结果，未命中时为空 Optional。
     */
    default Optional<IncidentCycleRow> findActiveForUpdatePreTrust(UUID tenantId, String fingerprint) {
        return sqlFindActiveForUpdatePreTrust(tenantId, fingerprint);
    }
    /**
     * 按租户及指纹读取 ACTIVE 实例和首事件周期，始终使用 FOR UPDATE 串行处理同一活动实例。
     *
     * @return 查询结果，未命中时为空 Optional。
     */
    Optional<IncidentCycleRow> sqlFindActiveForUpdatePreTrust(@Param("tenantId") UUID tenantId, @Param("fingerprint") String fingerprint);
    /**
     * 校验当前租户后，为先到达的恢复事件创建 RECOVERED 实例，首末及恢复事件均指向该事件，计数为 1，版本为 0。
     */
    default void insertRecoveredIncident(UUID tenantId, UUID incidentId, String fingerprint, int generation, UUID eventId, Instant now, String summary, UUID integrationId) {
        TenantContextHolder.current().requireTenant(tenantId);
        sqlInsertRecoveredIncident(tenantId, incidentId, fingerprint, generation, eventId, now, summary, integrationId);
    }
    /**
     * 为先到达的恢复事件创建 RECOVERED 实例，首末及恢复事件均指向该事件，计数为 1，版本为 0。
     */
    void sqlInsertRecoveredIncident(
            @Param("tenantId") UUID tenantId,
            @Param("incidentId") UUID incidentId,
            @Param("fingerprint") String fingerprint,
            @Param("generation") int generation,
            @Param("eventId") UUID eventId,
            @Param("now") Instant now,
            @Param("summary") String summary,
            @Param("integrationId") UUID integrationId);
    /**
     * 校验当前租户后，判断租户内实例是否已恢复且首事件本身为 RESOLVED，以识别仅由恢复事件建立的实例。
     *
     * @return 是否存在符合条件的实例。
     */
    default boolean isRecoveryOnlyIncident(UUID tenantId, UUID incidentId) {
        TenantContextHolder.current().requireTenant(tenantId);
        return sqlIsRecoveryOnlyIncident(tenantId, incidentId);
    }
    /**
     * 判断租户内实例是否已恢复且首事件本身为 RESOLVED，以识别仅由恢复事件建立的实例。
     *
     * @return 是否存在符合条件的实例。
     */
    boolean sqlIsRecoveryOnlyIncident(@Param("tenantId") UUID tenantId, @Param("incidentId") UUID incidentId);
    /**
     * 校验当前租户后，统计指定租户当前 ACTIVE 事件实例数量。
     *
     * @return 符合条件的记录数量。
     */
    default long countActiveIncidents(UUID tenantId) {
        TenantContextHolder.current().requireTenant(tenantId);
        return sqlCountActiveIncidents(tenantId);
    }
    /**
     * 统计指定租户当前 ACTIVE 事件实例数量。
     *
     * @return 符合条件的记录数量。
     */
    long sqlCountActiveIncidents(@Param("tenantId") UUID tenantId);
    /**
     * 可信租户上下文建立前，使用显式 tenantId 按租户、指纹及首事件周期查询实例，按代次及实例标识排序，供判断该周期是否已经建立实例。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    default List<IncidentCycleRow> findByCyclePreTrust(UUID tenantId, String fingerprint, String cycleRef) {
        return sqlFindByCyclePreTrust(tenantId, fingerprint, cycleRef);
    }
    /**
     * 按租户、指纹及首事件周期查询实例，按代次及实例标识排序，供判断该周期是否已经建立实例。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<IncidentCycleRow> sqlFindByCyclePreTrust(
            @Param("tenantId") UUID tenantId,
            @Param("fingerprint") String fingerprint,
            @Param("cycleRef") String cycleRef);
    /**
     * 可信租户上下文建立前，使用显式 tenantId 读取同租户及指纹的最大代次加 1，无记录时从 1 开始；本查询自身不加锁。
     *
     * @return 同租户、同指纹可使用的下一代次编号，从 1 开始。
     */
    default int nextGenerationPreTrust(UUID tenantId, String fingerprint) {
        return sqlNextGenerationPreTrust(tenantId, fingerprint);
    }
    /**
     * 读取同租户及指纹的最大代次加 1，无记录时从 1 开始；本查询自身不加锁。
     *
     * @return 同租户、同指纹可使用的下一代次编号，从 1 开始。
     */
    int sqlNextGenerationPreTrust(@Param("tenantId") UUID tenantId, @Param("fingerprint") String fingerprint);
    /**
     * 校验当前租户后，为新周期创建 ACTIVE 实例，初始化首末事件、计数、时间及版本，记录监控集成创建主体。
     */
    default void insertIncident(UUID tenantId, UUID incidentId, String fingerprint, int generation, UUID eventId, Instant now, String summary, UUID integrationId) {
        TenantContextHolder.current().requireTenant(tenantId);
        sqlInsertIncident(tenantId, incidentId, fingerprint, generation, eventId, now, summary, integrationId);
    }
    /**
     * 为新周期创建 ACTIVE 实例，初始化首末事件、计数、时间及版本，记录监控集成创建主体。
     */
    void sqlInsertIncident(
            @Param("tenantId") UUID tenantId,
            @Param("incidentId") UUID incidentId,
            @Param("fingerprint") String fingerprint,
            @Param("generation") int generation,
            @Param("eventId") UUID eventId,
            @Param("now") Instant now,
            @Param("summary") String summary,
            @Param("integrationId") UUID integrationId);
    /**
     * 校验当前租户后，将 FIRING 归并到 ACTIVE 实例，增加计数、维护不倒退的末次时间并刷新摘要，以 active 的实例标识及版本控制并发。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    default int aggregateFiring(UUID tenantId, IncidentCycleRow active, UUID eventId, Instant now, String summary, UUID integrationId) {
        TenantContextHolder.current().requireTenant(tenantId);
        return sqlAggregateFiring(tenantId, active, eventId, now, summary, integrationId);
    }
    /**
     * 将 FIRING 归并到 ACTIVE 实例，增加计数、维护不倒退的末次时间并刷新摘要，以 active 的实例标识及版本控制并发。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int sqlAggregateFiring(
            @Param("tenantId") UUID tenantId,
            @Param("active") IncidentCycleRow active,
            @Param("eventId") UUID eventId,
            @Param("now") Instant now,
            @Param("summary") String summary,
            @Param("integrationId") UUID integrationId);
    /**
     * 校验当前租户后，以恢复事件将 ACTIVE 实例转为 RECOVERED，更新末次及恢复时间并增加计数，仅在 active 预期版本匹配时生效。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    default int recoverIncident(UUID tenantId, IncidentCycleRow active, UUID eventId, Instant now, String summary, UUID integrationId) {
        TenantContextHolder.current().requireTenant(tenantId);
        return sqlRecoverIncident(tenantId, active, eventId, now, summary, integrationId);
    }
    /**
     * 以恢复事件将 ACTIVE 实例转为 RECOVERED，更新末次及恢复时间并增加计数，仅在 active 预期版本匹配时生效。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int sqlRecoverIncident(
            @Param("tenantId") UUID tenantId,
            @Param("active") IncidentCycleRow active,
            @Param("eventId") UUID eventId,
            @Param("now") Instant now,
            @Param("summary") String summary,
            @Param("integrationId") UUID integrationId);
    /**
     * 校验当前租户后，按租户及实例标识读取聚合详情和首事件周期，lock 为 true 时追加 FOR UPDATE。
     *
     * @return 查询结果，未命中时为空 Optional。
     */
    default Optional<IncidentCycleRow> findIncident(UUID tenantId, UUID incidentId, boolean lock) {
        TenantContextHolder.current().requireTenant(tenantId);
        return sqlFindIncident(tenantId, incidentId, lock);
    }
    /**
     * 按租户及实例标识读取聚合详情和首事件周期，lock 为 true 时追加 FOR UPDATE。
     *
     * @return 查询结果，未命中时为空 Optional。
     */
    Optional<IncidentCycleRow> sqlFindIncident(
            @Param("tenantId") UUID tenantId,
            @Param("incidentId") UUID incidentId,
            @Param("lock") boolean lock);
    /**
     * 校验当前租户后，按状态、指纹、工单关联及开启时间筛选并关联首事件周期，以选定时间及实例标识进行稳定游标分页并限制条数。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    default List<IncidentCycleRow> listIncidents(UUID tenantId, IncidentFilter filter, String sort, Instant cursorTime, UUID cursorId, int limit) {
        TenantContextHolder.current().requireTenant(tenantId);
        return sqlListIncidents(tenantId, filter, sort, cursorTime, cursorId, limit);
    }
    /**
     * 按状态、指纹、工单关联及开启时间筛选并关联首事件周期，以选定时间及实例标识进行稳定游标分页并限制条数。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<IncidentCycleRow> sqlListIncidents(
            @Param("tenantId") UUID tenantId,
            @Param("filter") IncidentFilter filter,
            @Param("sort") String sort,
            @Param("cursorTime") Instant cursorTime,
            @Param("cursorId") UUID cursorId,
            @Param("limit") int limit);
}
