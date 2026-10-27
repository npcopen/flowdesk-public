package com.flowdesk.workflow.start;

import com.flowdesk.workflow.model.recovery.WorkflowRecoveryModels;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 工作流收件箱、发件箱人工恢复流程的持久化接口。
 * 按租户读取恢复事实和消息来源，以锁版本、重放关联标识与租约限制状态迁移，并记录人工操作审计。
 */
@Mapper
public interface WorkflowRecoveryMapper {
    /**
     * 按租户和恢复标识读取完整恢复事实。
     * lock 为 true 时追加 FOR UPDATE，供人工重放事务检查状态、消息来源及锁版本。
     */
    List<WorkflowRecoveryModels.RecoveryFact> findRecovery(@Param("tenantId") byte[] tenantId,
            @Param("recoveryId") byte[] recoveryId,
            @Param("lock") boolean lock);

    /**
     * 读取收件箱重放所需的原始消息及处理状态。
     * 返回消息身份、契约、正文、摘要和链路标识；lock 为 true 时锁定源收件箱记录。
     */
    List<WorkflowRecoveryModels.ReplaySource> findSource(@Param("tenantId") byte[] tenantId,
            @Param("inboxId") byte[] inboxId,
            @Param("lock") boolean lock);

    /**
     * 读取指定发件箱的契约、状态及锁版本。
     * 按租户与 outboxId 定位，lock 为 true 时申请行锁，供重新排队前核对源记录。
     */
    List<WorkflowRecoveryModels.OutboxReplaySource> findOutboxSource(@Param("tenantId") byte[] tenantId,
            @Param("outboxId") byte[] outboxId,
            @Param("lock") boolean lock);

    /**
     * 读取恢复操作的审计锚点。
     * 按租户和 auditId 返回操作对象、操作人、结果及关联链路，供调用方核对先前操作。
     */
    List<WorkflowRecoveryModels.AuditAnchor> findAudit(@Param("tenantId") byte[] tenantId,
            @Param("auditId") byte[] auditId);

    /**
     * 将开放的收件箱恢复记录领取为重放中。
     * 核对来源与锁版本，保存人工操作信息和 attemptCorrelationId，并将 leaseUntil 写入 next_attempt_at。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int markReplaying(@Param("leaseUntil") Timestamp leaseUntil,
            @Param("now") Timestamp now,
            @Param("actorId") byte[] actorId,
            @Param("reason") String reason,
            @Param("attemptCorrelationId") byte[] attemptCorrelationId,
            @Param("tenantId") byte[] tenantId,
            @Param("recoveryRecoveryId") byte[] recoveryRecoveryId,
            @Param("recoverySourceRecordId") byte[] recoverySourceRecordId,
            @Param("recoveryLockVersion") long recoveryLockVersion);

    /**
     * 将开放的发件箱恢复记录标记为重放中。
     * 核对发件箱来源和锁版本，增加重放次数，记录人工重放原因、操作者及本次关联标识。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int markOutboxReplaying(@Param("now") Timestamp now,
            @Param("actorId") byte[] actorId,
            @Param("reason") String reason,
            @Param("attemptCorrelationId") byte[] attemptCorrelationId,
            @Param("tenantId") byte[] tenantId,
            @Param("recoveryRecoveryId") byte[] recoveryRecoveryId,
            @Param("sourceOutboxId") byte[] sourceOutboxId,
            @Param("recoveryLockVersion") long recoveryLockVersion);

    /**
     * 将待恢复发件箱重新加入自动发布队列。
     * 仅更新 RECOVERY_REQUIRED 且锁版本匹配的记录，重置尝试次数为 0，清除旧领取令牌、错误和 broker 确认时间。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int queueOutboxReplay(@Param("now") Timestamp now,
            @Param("tenantId") byte[] tenantId,
            @Param("sourceOutboxId") byte[] sourceOutboxId,
            @Param("sourceLockVersion") long sourceLockVersion);

    /**
     * 将本次失败的收件箱重放恢复为 OPEN。
     * 匹配 REPLAYING、本次关联标识和租约时间，避免旧结果覆盖另一轮重放，并保存最新错误。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int reopenFailedReplay(@Param("errorCode") String errorCode,
            @Param("errorSummary") String errorSummary,
            @Param("now") Timestamp now,
            @Param("tenantId") byte[] tenantId,
            @Param("recoveryId") byte[] recoveryId,
            @Param("attemptCorrelationId") byte[] attemptCorrelationId,
            @Param("leaseUntil") Timestamp leaseUntil);

    /**
     * 将已得到终态结果的收件箱重放标记为已解决。
     * 仅处理匹配本次 attemptCorrelationId 和 leaseUntil 的 REPLAYING 记录，清除租约并记录解决时间。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int resolveTerminal(@Param("now") Timestamp now,
            @Param("tenantId") byte[] tenantId,
            @Param("recoveryId") byte[] recoveryId,
            @Param("attemptCorrelationId") byte[] attemptCorrelationId,
            @Param("leaseUntil") Timestamp leaseUntil);

    /**
     * 保存人工恢复操作的审计事实。
     * 目标固定为 MESSAGE_RECOVERY，保存操作人、原因、执行结果、错误码及关联链路。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int insertAudit(@Param("tenantId") byte[] tenantId,
            @Param("auditId") byte[] auditId,
            @Param("action") String action,
            @Param("recoveryId") byte[] recoveryId,
            @Param("outcome") String outcome,
            @Param("actorId") byte[] actorId,
            @Param("reason") String reason,
            @Param("errorCode") String errorCode,
            @Param("correlationId") byte[] correlationId,
            @Param("causationId") byte[] causationId,
            @Param("now") Timestamp now);
    /**
     * 按租户和筛选条件分页读取恢复记录。
     * 支持方向、状态、死信、错误码及恢复时间范围，按恢复或死信时间与恢复标识排序，最多返回 limit 条。
     */
    List<WorkflowRecoveryModels.RecoveryFact> findRecoveries(
            @Param("tenantId") byte[] tenantId, @Param("direction") String direction,
            @Param("state") String state, @Param("deadLettered") Boolean deadLettered,
            @Param("lastErrorCode") String lastErrorCode, @Param("from") Instant from,
            @Param("to") Instant to, @Param("ascending") boolean ascending,
            @Param("deadLetterSort") boolean deadLetterSort, @Param("cursorTime") Instant cursorTime,
            @Param("cursorId") byte[] cursorId, @Param("limit") int limit);
}
