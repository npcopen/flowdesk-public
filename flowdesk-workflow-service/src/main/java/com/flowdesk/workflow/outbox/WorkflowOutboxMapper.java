package com.flowdesk.workflow.outbox;

import com.flowdesk.workflow.model.WorkflowOutboxModels;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.sql.Timestamp;
import java.util.List;

/**
 * 工作流实例建立、审批终结事件的发件箱持久化接口。
 * 提供跨租户候选扫描、租约领取、发布结果回写及恢复记录维护；事务边界与重试决策由调用方服务负责。
 */
@Mapper
public interface WorkflowOutboxMapper {
    /**
     * 跨租户扫描实例建立、审批终结两个 v1 契约的发件箱。
     * 仅处理 WORKFLOW_INSTANCE 聚合，按到期时间稳定排序，包含到期 PENDING 与租约过期 PUBLISHING，最多读取 limit 条。
     */
    List<WorkflowOutboxModels.Candidate> findCandidates(@Param("establishedContract") String establishedContract,
            @Param("finalizedContract") String finalizedContract,
            @Param("maxAttempts") int maxAttempts,
            @Param("now") Timestamp now,
            @Param("leaseCutoff") Timestamp leaseCutoff,
            @Param("limit") int limit);

    /**
     * 领取一条到期的待发布消息并增加尝试次数。
     * 匹配租户、状态、候选尝试次数与锁版本，且尚未达到 maxAttempts；token 标识本轮领取者。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int claimPendingForSend(@Param("token") String token,
            @Param("now") Timestamp now,
            @Param("candidateTenantId") byte[] candidateTenantId,
            @Param("candidateOutboxId") byte[] candidateOutboxId,
            @Param("candidateAttemptCount") int candidateAttemptCount,
            @Param("maxAttempts") int maxAttempts,
            @Param("candidateLockVersion") long candidateLockVersion);

    /**
     * 接管租约已过期且仍可重试的发布任务。
     * 同时匹配原领取者、尝试次数和锁版本，要求 locked_at 不晚于 leaseCutoff，接管后增加尝试次数。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int takeOverForSend(@Param("token") String token,
            @Param("now") Timestamp now,
            @Param("candidateTenantId") byte[] candidateTenantId,
            @Param("candidateOutboxId") byte[] candidateOutboxId,
            @Param("candidateAttemptCount") int candidateAttemptCount,
            @Param("maxAttempts") int maxAttempts,
            @Param("candidateLockedBy") String candidateLockedBy,
            @Param("candidateLockVersion") long candidateLockVersion,
            @Param("leaseCutoff") Timestamp leaseCutoff);

    /**
     * 领取已经耗尽尝试次数的待发布消息以执行恢复登记。
     * 要求到期、attempt_count 等于 maxAttempts 且锁版本匹配；设置新令牌和租约，不再增加发送次数。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int claimPendingForRecovery(@Param("token") String token,
            @Param("now") Timestamp now,
            @Param("candidateTenantId") byte[] candidateTenantId,
            @Param("candidateOutboxId") byte[] candidateOutboxId,
            @Param("maxAttempts") int maxAttempts,
            @Param("candidateLockVersion") long candidateLockVersion);

    /**
     * 接管尝试次数已耗尽且租约过期的发布记录。
     * 核对原领取者及锁版本后更新令牌与时间，为后续转入人工恢复取得所有权。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int takeOverForRecovery(@Param("token") String token,
            @Param("now") Timestamp now,
            @Param("candidateTenantId") byte[] candidateTenantId,
            @Param("candidateOutboxId") byte[] candidateOutboxId,
            @Param("maxAttempts") int maxAttempts,
            @Param("candidateLockedBy") String candidateLockedBy,
            @Param("candidateLockVersion") long candidateLockVersion,
            @Param("leaseCutoff") Timestamp leaseCutoff);

    /**
     * 在 broker 确认后将持有的消息标记为已发布。
     * 核对租户、尝试次数、领取令牌、锁版本及仍有效的租约，清除锁定信息和错误，保存确认时间。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int markPublished(@Param("now") Timestamp now,
            @Param("rowTenantId") byte[] rowTenantId,
            @Param("rowOutboxId") byte[] rowOutboxId,
            @Param("rowAttemptCount") int rowAttemptCount,
            @Param("rowClaimToken") String rowClaimToken,
            @Param("rowLockVersion") long rowLockVersion,
            @Param("leaseCutoff") Timestamp leaseCutoff);

    /**
     * 在发件箱重放完成后解决对应恢复记录。
     * 仅将同租户、OUTBOX 方向、同源发件箱且处于 REPLAYING 的记录更新为 RESOLVED，并清除下次尝试时间。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int resolveReplay(@Param("now") Timestamp now,
            @Param("rowTenantId") byte[] rowTenantId,
            @Param("rowOutboxId") byte[] rowOutboxId);

    /**
     * 将本轮发布失败的消息重新放回待发布状态。
     * 持有者与有效租约校验通过后保存错误和下次尝试时间，清除领取信息与旧 broker 确认时间。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int markPending(@Param("next") Timestamp next,
            @Param("errorCode") String errorCode,
            @Param("now") Timestamp now,
            @Param("rowTenantId") byte[] rowTenantId,
            @Param("rowOutboxId") byte[] rowOutboxId,
            @Param("rowAttemptCount") int rowAttemptCount,
            @Param("rowClaimToken") String rowClaimToken,
            @Param("rowLockVersion") long rowLockVersion,
            @Param("leaseCutoff") Timestamp leaseCutoff);

    /**
     * 将已耗尽发送次数且仍由当前执行者持有的消息转入人工恢复。
     * 要求 attempt_count 等于 maxAttempts、令牌和锁版本匹配且租约有效，保存错误并清除锁定及 broker 确认信息。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int moveToRecovery(@Param("now") Timestamp now,
            @Param("errorCode") String errorCode,
            @Param("rowTenantId") byte[] rowTenantId,
            @Param("rowOutboxId") byte[] rowOutboxId,
            @Param("maxAttempts") int maxAttempts,
            @Param("rowClaimToken") String rowClaimToken,
            @Param("rowLockVersion") long rowLockVersion,
            @Param("leaseCutoff") Timestamp leaseCutoff);

    /**
     * 保存发件箱进入人工恢复流程的失败审计。
     * 使用服务身份记录错误摘要及关联链路，供后续追踪发布失败和恢复登记结果。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int insertRecoveryAudit(@Param("rowTenantId") byte[] rowTenantId,
            @Param("auditId") byte[] auditId,
            @Param("recoveryId") byte[] recoveryId,
            @Param("summary") String summary,
            @Param("errorCode") String errorCode,
            @Param("rowCorrelationId") byte[] rowCorrelationId,
            @Param("now") Timestamp now);

    /**
     * 为当前发件箱新增 OUTBOX 方向的开放恢复记录。
     * 保留来源发件箱、业务意图、尝试次数与错误摘要，初始化重放次数及锁版本。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int insertRecovery(@Param("rowTenantId") byte[] rowTenantId,
            @Param("recoveryId") byte[] recoveryId,
            @Param("rowOutboxId") byte[] rowOutboxId,
            @Param("rowBusinessIntentKey") byte[] rowBusinessIntentKey,
            @Param("maxAttempts") int maxAttempts,
            @Param("code") String code,
            @Param("summary") String summary,
            @Param("now") Timestamp now);

    /**
     * 查询同租户、同源发件箱的 OUTBOX 恢复记录。
     * 返回已保存的业务意图和恢复状态，供调用方核对恢复事实是否已存在。
     */
    List<WorkflowOutboxModels.Recovery> findRecovery(@Param("rowTenantId") byte[] rowTenantId,
            @Param("rowOutboxId") byte[] rowOutboxId);

    /**
     * 按完整领取身份读取待发送消息正文及元数据。
     * 要求租户、发件箱、PUBLISHING、尝试次数、token 和锁版本全部匹配；匹配失败时返回空列表。
     */
    List<WorkflowOutboxModels.Claimed> findClaimed(@Param("tenantId") byte[] tenantId,
            @Param("outboxId") byte[] outboxId,
            @Param("attempt") int attempt,
            @Param("token") String token,
            @Param("lockVersion") long lockVersion);
}
