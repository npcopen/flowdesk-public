package com.flowdesk.ticket.sla;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.sql.Timestamp;
import com.flowdesk.ticket.model.sla.PendingSlaNotification;
import com.flowdesk.ticket.model.sla.SlaCandidateRow;
import com.flowdesk.ticket.model.sla.SlaClockRow;
import com.flowdesk.ticket.model.sla.SlaHighWaterRow;
import com.flowdesk.ticket.model.sla.SlaPolicyRow;
import com.flowdesk.ticket.model.sla.SlaTriggerDetailRow;
import com.flowdesk.ticket.model.sla.SlaTriggerRow;

/**
 * SLA 策略读取、时钟状态及触发记录的持久化接口。
 * 按租户隔离计时周期，提供到期扫描、变更游标及通知收件人快照；身份和事务校验由业务实现负责。
 */
@Mapper
public interface SlaPersistenceMapper {
    /**
     * 按租户及策略标识读取策略详情，包含首响应、解决时限、提醒提前量和版本，不限制策略状态。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<SlaPolicyRow> findSlaPolicySelect(@Param("tenantId") byte[] tenantId, @Param("slaPolicyId") byte[] slaPolicyId);
    /**
     * 仅返回指定租户及策略标识下状态为 ACTIVE 的策略，供新建时钟时读取有效时限配置。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<SlaPolicyRow> findActiveSlaPolicySelect(@Param("tenantId") byte[] tenantId, @Param("slaPolicyId") byte[] slaPolicyId);
    /**
     * 按租户和可选状态筛选策略；排序列仅允许策略编码或名称，并以排序值和策略标识组成稳定游标，最多 queryLimit 条。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<SlaPolicyRow> findSlaPoliciesSelect(@Param("tenantId") byte[] tenantId, @Param("status") String status, @Param("cursorValue") String cursorValue, @Param("cursorId") UUID cursorId, @Param("queryLimit") int queryLimit, @Param("sortColumn") String sortColumn, @Param("ascending") boolean ascending);
    /**
     * 创建指定工单及 cycleNo 的 RUNNING 时钟，保存起始、到期、提醒时间和策略时限快照，版本初始化为 0。
     *
     * @return 实际影响行数，调用方据此判断条件更新、插入或续租是否生效。
     */
    int insertSlaClockUpdate(@Param("tenantId") byte[] tenantId, @Param("slaClockId") byte[] slaClockId, @Param("ticketId") byte[] ticketId, @Param("slaPolicyId") byte[] slaPolicyId, @Param("clockType") String clockType, @Param("startedAt") Timestamp startedAt, @Param("deadlineAt") Timestamp deadlineAt, @Param("reminderDueAt") Timestamp reminderDueAt, @Param("durationSeconds") long durationSeconds, @Param("reminderBeforeSeconds") Long reminderBeforeSeconds, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("cycleNo") int cycleNo);
    /**
     * 按租户、工单及 clockType 读取最大 cycleNo 的最新计时周期，最多返回一条，不加行锁。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<SlaClockRow> findSlaClockSelect(@Param("tenantId") byte[] tenantId, @Param("ticketId") byte[] ticketId, @Param("clockType") String clockType);
    /**
     * 按租户、工单及类型读取最新计时周期，追加 FOR UPDATE，供同一事务内检查并更新时钟。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<SlaClockRow> findSlaClockForUpdateSelect(@Param("tenantId") byte[] tenantId, @Param("ticketId") byte[] ticketId, @Param("clockType") String clockType);
    /**
     * 按租户及时钟标识读取详情并加 FOR UPDATE 行锁，供触发或里程碑处理事务使用。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<SlaClockRow> findSlaClockByIdForUpdateSelect(@Param("tenantId") byte[] tenantId, @Param("slaClockId") byte[] slaClockId);
    /**
     * 按租户及时钟标识读取计时状态、策略快照、截止时间及当前版本，不加行锁。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<SlaClockRow> findSlaClockByIdSelect(@Param("tenantId") byte[] tenantId, @Param("slaClockId") byte[] slaClockId);
    /**
     * 扫描租户内提醒已到期或已超过截止时间的 RUNNING 时钟，以 updatedAt 和时钟标识升序游标分页，最多 limit 条。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<SlaCandidateRow> findSlaCandidatesSelect(@Param("tenantId") byte[] tenantId, @Param("now") Instant now, @Param("cursorUpdatedAt") Instant cursorUpdatedAt, @Param("cursorClockId") UUID cursorClockId, @Param("limit") int limit);
    /**
     * 扫描租户内全部 RUNNING 时钟，以更新时间及时钟标识升序分页，不限定是否到期，供索引重建使用。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<SlaCandidateRow> findRunningSlaCandidatesSelect(@Param("tenantId") byte[] tenantId, @Param("cursorUpdatedAt") Instant cursorUpdatedAt, @Param("cursorClockId") UUID cursorClockId, @Param("limit") int limit);
    /**
     * 读取租户内按更新时间和时钟标识倒序排列的第一条，作为重建或增量追赶的高水位边界。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<SlaHighWaterRow> findSlaHighWaterSelect(@Param("tenantId") byte[] tenantId);
    /**
     * 读取严格位于 updatedAt 和 clockId 组合游标之后的时钟，不限制状态；按相同组合升序分页，最多 limit 条。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<SlaCandidateRow> findSlaChangesAfterSelect(@Param("tenantId") byte[] tenantId, @Param("updatedAt") Instant updatedAt, @Param("clockId") UUID clockId, @Param("limit") int limit);
    /**
     * 读取更新时间不早于 updatedAt 的时钟，包含边界时间，按更新时间及时钟标识升序返回最多 limit 条。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<SlaCandidateRow> findSlaChangesAtOrAfterSelect(@Param("tenantId") byte[] tenantId, @Param("updatedAt") Timestamp updatedAt, @Param("limit") int limit);
    /**
     * 分页枚举拥有 SLA 时钟的去重租户标识，按租户标识升序读取 afterTenantId 之后的批次。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<UUID> findTenantIdsWithSlaClocksSelect(@Param("afterTenantId") UUID afterTenantId, @Param("limit") int limit);
    /**
     * 按租户和工单读取全部计时周期，优先最新 cycleNo，再按时钟类型及时钟标识稳定排序。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<SlaClockRow> findSlaClocksSelect(@Param("tenantId") byte[] tenantId, @Param("ticketId") byte[] ticketId);
    /**
     * 批量查询租户内指定时钟集合的触发阶段、到期及触发时间，按到期时间及时钟标识排序；调用方需提供非空集合。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<SlaTriggerRow> findSlaTriggersSelect(@Param("tenantId") byte[] tenantId, @Param("slaClockIds") Collection<UUID> slaClockIds);
    /**
     * 仅将 RUNNING 且版本匹配的时钟转为 SATISFIED_ON_TIME，记录里程碑和满足时间；更新时间至少前进 1 微秒，以便增量扫描感知变化。
     *
     * @return 实际影响行数，调用方据此判断条件更新、插入或续租是否生效。
     */
    int updateSlaClockSatisfiedOnTimeUpdate(@Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("now3") Timestamp now3, @Param("now4") Timestamp now4, @Param("actorType") String actorType, @Param("actorId") byte[] actorId, @Param("tenantId") byte[] tenantId, @Param("slaClockId") byte[] slaClockId, @Param("expectedLockVersion") long expectedLockVersion);
    /**
     * 仅将 RUNNING 且版本匹配的时钟转为 BREACHED，记录违约时间；增加版本并保证更新时间单调递增。
     *
     * @return 实际影响行数，调用方据此判断条件更新、插入或续租是否生效。
     */
    int updateSlaClockBreachedUpdate(@Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("now3") Timestamp now3, @Param("actorType") String actorType, @Param("actorId") byte[] actorId, @Param("tenantId") byte[] tenantId, @Param("slaClockId") byte[] slaClockId, @Param("expectedLockVersion") long expectedLockVersion);
    /**
     * 仅将 BREACHED 且版本匹配的时钟转为 SATISFIED_LATE，记录里程碑及满足时间；增加版本并保证更新时间单调递增。
     *
     * @return 实际影响行数，调用方据此判断条件更新、插入或续租是否生效。
     */
    int updateSlaClockSatisfiedLateUpdate(@Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("now3") Timestamp now3, @Param("now4") Timestamp now4, @Param("actorType") String actorType, @Param("actorId") byte[] actorId, @Param("tenantId") byte[] tenantId, @Param("slaClockId") byte[] slaClockId, @Param("expectedLockVersion") long expectedLockVersion);
    /**
     * 扫描尚无任何收件人快照的触发记录，关联得到工单和阶段，以 triggerId 为升序游标返回待补发候选。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<PendingSlaNotification> findPendingSlaNotificationsSelect(@Param("tenantId") byte[] tenantId, @Param("afterTriggerId") UUID afterTriggerId, @Param("limit") int limit);
    /**
     * 按租户、时钟及阶段读取触发效果键、到期时间、触发时间和发布键；lock 为 true 时追加 FOR UPDATE。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<SlaTriggerDetailRow> findSlaTriggerSelect(@Param("tenantId") byte[] tenantId, @Param("slaClockId") byte[] slaClockId, @Param("stage") String stage, @Param("lock") boolean lock);
    /**
     * 写入指定时钟阶段的触发事实，保存业务效果键和通知发布键，以 SCHEDULER 主体记录触发及创建时间。
     *
     * @return 实际影响行数，调用方据此判断条件更新、插入或续租是否生效。
     */
    int insertSlaTriggerUpdate(@Param("tenantId") byte[] tenantId, @Param("triggerId") byte[] triggerId, @Param("slaClockId") byte[] slaClockId, @Param("stage") String stage, @Param("effectKey") byte[] effectKey, @Param("dueAt") Timestamp dueAt, @Param("triggeredAt") Timestamp triggeredAt, @Param("publicationKey") byte[] publicationKey, @Param("triggeredAt2") Timestamp triggeredAt2);
    /**
     * 为触发记录保存一位已确定收件人的快照及创建主体，供后续重试沿用同一收件人集合。
     *
     * @return 实际影响行数，调用方据此判断条件更新、插入或续租是否生效。
     */
    int insertSlaTriggerRecipientSnapshotUpdate(@Param("tenantId") byte[] tenantId, @Param("triggerId") byte[] triggerId, @Param("recipient") byte[] recipient, @Param("now") Timestamp now, @Param("createdByType") String createdByType, @Param("createdById") byte[] createdById);
    /**
     * 按租户及触发标识读取已保存的收件人快照，按用户标识稳定排序。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<UUID> findSlaTriggerRecipientSnapshotSelect(@Param("tenantId") byte[] tenantId, @Param("triggerId") byte[] triggerId);
    /**
     * 以调度器主体刷新时钟更新时间；若现有时间不小于 now 则递增 1 微秒，确保触发相关变化可被增量扫描发现。
     *
     * @return 实际影响行数，调用方据此判断条件更新、插入或续租是否生效。
     */
    int updateSlaClockUpdatedAtUpdate(@Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("tenantId") byte[] tenantId, @Param("slaClockId") byte[] slaClockId);
}
