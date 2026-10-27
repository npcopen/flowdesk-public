package com.flowdesk.workflow.notification;

import com.flowdesk.workflow.model.WorkflowNotificationOutboxModels;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.sql.Timestamp;
import java.util.List;

/**
 * 工作流通知发件箱和人工恢复记录的持久化接口。
 * 候选扫描面向各租户，领取和回写按租户定位；通过领取令牌、尝试次数、锁版本与租约时间协调并发发布。
 */
@Mapper
public interface WorkflowNotificationOutboxMapper {
    /**
     * 跨租户扫描指定通知契约、版本及聚合类型的发布候选。
     * 读取到期 PENDING 或租约过期 PUBLISHING，要求尝试次数不超过 maxAttempts，按到期时间稳定排序并限制条数。
     */
    List<WorkflowNotificationOutboxModels.Candidate> findCandidates(@Param("contractRef") String contractRef,
            @Param("contractVersion") int contractVersion,
            @Param("aggregateType") String aggregateType,
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
     * 将本轮发布失败的消息重新放回待发布状态。
     * 持有者与有效租约校验通过后保存错误和下次尝试时间，清除领取信息与旧 broker 确认时间。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int markPending(@Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("errorCode") String errorCode,
            @Param("now") Timestamp now,
            @Param("rowTenantId") byte[] rowTenantId,
            @Param("rowOutboxId") byte[] rowOutboxId,
            @Param("rowAttemptCount") int rowAttemptCount,
            @Param("rowClaimToken") String rowClaimToken,
            @Param("rowLockVersion") long rowLockVersion,
            @Param("leaseCutoff") Timestamp leaseCutoff);

    /**
     * 将持有的发布任务转为需要人工恢复。
     * 在尝试次数、领取令牌、锁版本和租约均匹配时写入 RECOVERY_REQUIRED，保存错误并释放领取信息。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int moveToRecovery(@Param("now") Timestamp now,
            @Param("code") String code,
            @Param("rowTenantId") byte[] rowTenantId,
            @Param("rowOutboxId") byte[] rowOutboxId,
            @Param("rowAttemptCount") int rowAttemptCount,
            @Param("rowClaimToken") String rowClaimToken,
            @Param("rowLockVersion") long rowLockVersion,
            @Param("leaseCutoff") Timestamp leaseCutoff);

    /**
     * 保存通知发件箱发布失败的服务审计。
     * value2 作为 action_code，目标固定为 OUTBOX_EVENT，记录来源发件箱、错误、摘要以及关联和因果标识。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int insertRecoveryAudit(@Param("rowTenantId") byte[] rowTenantId,
            @Param("auditId") byte[] auditId,
            @Param("value2") String value2,
            @Param("rowOutboxId") byte[] rowOutboxId,
            @Param("summary") String summary,
            @Param("code") String code,
            @Param("rowCorrelationId") byte[] rowCorrelationId,
            @Param("rowCausationId") byte[] rowCausationId,
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
            @Param("rowAttemptCount") int rowAttemptCount,
            @Param("code") String code,
            @Param("summary") String summary,
            @Param("now") Timestamp now);

    /**
     * 读取并锁定同租户、同源通知发件箱的恢复记录。
     * 使用 FOR UPDATE 返回恢复标识、业务意图、状态、尝试次数和锁版本，供恢复记录复用或重新打开。
     */
    List<WorkflowNotificationOutboxModels.Recovery> findRecovery(@Param("rowTenantId") byte[] rowTenantId,
            @Param("rowOutboxId") byte[] rowOutboxId);

    /**
     * 通知重放再次失败时重新打开现有恢复记录。
     * 仅更新 REPLAYING 且 existingLockVersion 匹配的记录，刷新尝试次数、错误及摘要，并清除解决时间。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int reopenRecovery(@Param("rowAttemptCount") int rowAttemptCount,
            @Param("code") String code,
            @Param("summary") String summary,
            @Param("now") Timestamp now,
            @Param("rowTenantId") byte[] rowTenantId,
            @Param("recoveryId") byte[] recoveryId,
            @Param("existingLockVersion") long existingLockVersion);

    /**
     * 在发件箱重放完成后解决对应恢复记录。
     * 仅将同租户、OUTBOX 方向、同源发件箱且处于 REPLAYING 的记录更新为 RESOLVED，并清除下次尝试时间。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int resolveReplay(@Param("now") Timestamp now,
            @Param("tenantId") byte[] tenantId,
            @Param("outboxId") byte[] outboxId);

    /**
     * 按完整领取身份读取待发送消息正文及元数据。
     * 要求租户、发件箱、PUBLISHING、尝试次数、token 和锁版本全部匹配；匹配失败时返回空列表。
     */
    List<WorkflowNotificationOutboxModels.Claimed> findClaimed(@Param("tenantId") byte[] tenantId,
            @Param("outboxId") byte[] outboxId,
            @Param("attempts") int attempts,
            @Param("token") String token,
            @Param("lockVersion") long lockVersion);
}
