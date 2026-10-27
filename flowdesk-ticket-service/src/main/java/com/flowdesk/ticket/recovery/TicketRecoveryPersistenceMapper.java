package com.flowdesk.ticket.recovery;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.sql.Timestamp;
import com.flowdesk.ticket.model.recovery.Audit;
import com.flowdesk.ticket.model.recovery.InboxSource;
import com.flowdesk.ticket.model.recovery.OutboxSource;
import com.flowdesk.ticket.model.recovery.Recovery;

/**
 * 工单消息恢复持久化接口，提供恢复列表、来源读取、重放状态及操作审计。
 * 租户边界与事务组织由调用方负责；本接口执行对应 SQL 并返回类型化结果。
 */
@Mapper
public interface TicketRecoveryPersistenceMapper {
    /**
     * 按租户和恢复 ID 读取恢复详情，包含来源方向、重放信息和锁版本。
     * lock 为 true 时附加 FOR UPDATE，行锁需在事务内使用。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<Recovery> findSelect(@Param("tenantId") byte[] tenantId, @Param("recoveryId") byte[] recoveryId, @Param("lock") boolean lock);
    /**
     * 按方向、状态、死信标记、错误码和时间范围筛选恢复项，以选定时间列和恢复 ID 组成游标分页。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<Recovery> listSelect(@Param("tenantId") byte[] tenantId, @Param("direction") String direction, @Param("state") String state, @Param("error") String error, @Param("from") Instant from, @Param("to") Instant to, @Param("cursorTime") Instant cursorTime, @Param("cursorId") UUID cursorId, @Param("limit") int limit, @Param("deadLettered") Boolean deadLettered, @Param("deadSort") boolean deadSort, @Param("ascending") boolean ascending);
    /**
     * 锁定读取恢复项对应的 Inbox 原文、契约及状态，供沿原消费路径重放。
     * lock 为 true 时附加 FOR UPDATE，行锁需在事务内使用。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<InboxSource> inboxSourceSelect(@Param("tenantId") byte[] tenantId, @Param("inboxId") byte[] inboxId, @Param("lock") boolean lock);
    /**
     * 锁定读取 Outbox 来源，并联查工单流程启动关联状态及版本，供受控重放。
     * lock 为 true 时附加 FOR UPDATE，行锁需在事务内使用。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<OutboxSource> outboxSourceSelect(@Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("lock") boolean lock);
    /**
     * 按审计 ID 查询恢复操作记录，供识别重复请求并核验已有操作意图。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<Audit> auditSelect(@Param("tenantId") byte[] tenantId, @Param("auditId") byte[] auditId);
    /**
     * 按预期版本将 RECOVERY_REQUIRED 消息重新置为 PENDING，清零发送次数和错误信息以启动重放。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int acceptOutboxUpdate(@Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("lockVersion") long lockVersion);
    /**
     * 按预期关联版本将 RECOVERY_REQUIRED 流程启动关联退回 REQUESTED，记录发起重放的用户。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int acceptOutboxUpdate2(@Param("now") Timestamp now, @Param("actorId") byte[] actorId, @Param("tenantId") byte[] tenantId, @Param("ticketId") byte[] ticketId, @Param("linkLockVersion") long linkLockVersion);
    /**
     * 按来源方向、来源 ID 和锁版本将 OPEN 恢复项置为 REPLAYING，记录重放用户、原因、关联 ID 和租约。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int markReplayingUpdate(@Param("replayLeaseUntil") Timestamp replayLeaseUntil, @Param("now") Timestamp now, @Param("actorId") byte[] actorId, @Param("reason") String reason, @Param("attemptCorrelation") byte[] attemptCorrelation, @Param("now2") Timestamp now2, @Param("actorId2") byte[] actorId2, @Param("tenantId") byte[] tenantId, @Param("recoveryId") byte[] recoveryId, @Param("direction") String direction, @Param("sourceRecordId") byte[] sourceRecordId, @Param("lockVersion") long lockVersion);
    /**
     * 仅当本次重放关联 ID 和租约截止时间仍匹配时，将未达到终态的恢复项退回 OPEN。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int reopenUpdate(@Param("error") String error, @Param("now") Timestamp now, @Param("tenantId") byte[] tenantId, @Param("recoveryId") byte[] recoveryId, @Param("attemptCorrelation") byte[] attemptCorrelation, @Param("leaseUntil") Timestamp leaseUntil);
    /**
     * 仅当本次关联 ID 和租约匹配时，完成 Inbox 方向的 REPLAYING 恢复项并清除租约。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int resolveTerminalUpdate(@Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("tenantId") byte[] tenantId, @Param("recoveryId") byte[] recoveryId, @Param("attemptCorrelation") byte[] attemptCorrelation, @Param("leaseUntil") Timestamp leaseUntil);
    /**
     * 保存用户对消息恢复项的操作审计，记录动作、结果、原因及请求链路。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int insertAuditUpdate(@Param("tenantId") byte[] tenantId, @Param("auditId") byte[] auditId, @Param("action") String action, @Param("recoveryId") byte[] recoveryId, @Param("outcome") String outcome, @Param("actorId") byte[] actorId, @Param("reason") String reason, @Param("error") String error, @Param("correlation") byte[] correlation, @Param("causation") byte[] causation, @Param("now") Timestamp now);
}
