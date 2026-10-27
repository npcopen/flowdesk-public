package com.flowdesk.ticket.outbox;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.sql.Timestamp;
import com.flowdesk.ticket.model.outbox.Candidate;
import com.flowdesk.ticket.model.outbox.ClaimedOutbox;
import com.flowdesk.ticket.model.outbox.LinkState;
import com.flowdesk.ticket.model.outbox.RecoveryState;

/**
 * 工作流启动消息 Outbox 接口，维护发送认领、重试和工单流程关联的恢复状态。
 * 租户边界与事务组织由调用方负责；本接口执行对应 SQL 并返回类型化结果。
 */
@Mapper
public interface TicketOutboxPersistenceMapper {
    /**
     * 跨租户扫描契约匹配的到期 PENDING 或租约过期 PUBLISHING 消息，按待处理时间、租户和 ID 限量排序。
     *
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<Candidate> findCandidatesSelect(@Param("contractRef") String contractRef, @Param("contractVersion") int contractVersion, @Param("aggregateType") String aggregateType, @Param("maxAutomaticSends") int maxAutomaticSends, @Param("now") Timestamp now, @Param("leaseCutoff") Timestamp leaseCutoff, @Param("limit") int limit);
    /**
     * 认领已到期的 PENDING 消息，写入发送令牌和租约时间，并递增发送次数与锁版本。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int claimForSendUpdate(@Param("claimToken") String claimToken, @Param("claimedAt") Timestamp claimedAt, @Param("claimedAt2") Timestamp claimedAt2, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("attemptCount") int attemptCount, @Param("maxAutomaticSends") int maxAutomaticSends, @Param("lockVersion") long lockVersion, @Param("claimedAt3") Timestamp claimedAt3);
    /**
     * 接管租约已过期的 PUBLISHING 消息，核对原令牌、次数和版本后重新认领并增加发送次数。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int claimForSendUpdate2(@Param("claimToken") String claimToken, @Param("claimedAt") Timestamp claimedAt, @Param("claimedAt2") Timestamp claimedAt2, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("attemptCount") int attemptCount, @Param("maxAutomaticSends") int maxAutomaticSends, @Param("lockedBy") String lockedBy, @Param("lockVersion") long lockVersion, @Param("leaseCutoff") Timestamp leaseCutoff);
    /**
     * 认领已达自动发送上限的到期 PENDING 消息，供进入恢复流程；不再增加发送次数。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int claimForRecoveryUpdate(@Param("claimToken") String claimToken, @Param("claimedAt") Timestamp claimedAt, @Param("claimedAt2") Timestamp claimedAt2, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("maxAutomaticSends") int maxAutomaticSends, @Param("lockVersion") long lockVersion, @Param("claimedAt3") Timestamp claimedAt3);
    /**
     * 接管已达发送上限且租约过期的 PUBLISHING 消息，供恢复处理；不再增加发送次数。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int claimForRecoveryUpdate2(@Param("claimToken") String claimToken, @Param("claimedAt") Timestamp claimedAt, @Param("claimedAt2") Timestamp claimedAt2, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("maxAutomaticSends") int maxAutomaticSends, @Param("lockedBy") String lockedBy, @Param("lockVersion") long lockVersion, @Param("leaseCutoff") Timestamp leaseCutoff);
    /**
     * 在发送令牌、次数、版本和有效租约均匹配时确认 PUBLISHED，记录 Broker 确认时间并清除租约。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int markPublishedUpdate(@Param("confirmedAt") Timestamp confirmedAt, @Param("confirmedAt2") Timestamp confirmedAt2, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("attemptCount") int attemptCount, @Param("claimToken") String claimToken, @Param("lockVersion") long lockVersion, @Param("leaseCutoff") Timestamp leaseCutoff);
    /**
     * 启动消息发布成功后，将同一 Outbox 来源的 REPLAYING 恢复项置为 RESOLVED。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int markPublishedUpdate2(@Param("confirmedAt") Timestamp confirmedAt, @Param("confirmedAt2") Timestamp confirmedAt2, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId);
    /**
     * 在当前发送租约仍有效时退回 PENDING，保存错误码及下次尝试时间并释放租约。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int markPendingUpdate(@Param("nextAttemptAt") Timestamp nextAttemptAt, @Param("errorCode") String errorCode, @Param("completedAt") Timestamp completedAt, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("attemptCount") int attemptCount, @Param("claimToken") String claimToken, @Param("lockVersion") long lockVersion, @Param("leaseCutoff") Timestamp leaseCutoff);
    /**
     * 在发送次数已达上限且当前租约有效时，将启动消息置为 RECOVERY_REQUIRED 并记录失败原因。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int moveToRecoveryUpdate(@Param("completedAt") Timestamp completedAt, @Param("errorCode") String errorCode, @Param("completedAt2") Timestamp completedAt2, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("maxAutomaticSends") int maxAutomaticSends, @Param("claimToken") String claimToken, @Param("lockVersion") long lockVersion, @Param("leaseCutoff") Timestamp leaseCutoff);
    /**
     * 读取工单流程启动关联的当前状态和版本，供消息耗尽重试后同步关联恢复状态。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<LinkState> moveToRecoverySelect2(@Param("tenantId") byte[] tenantId, @Param("aggregateId") byte[] aggregateId);
    /**
     * 按关联锁版本将 REQUESTED 流程启动关联转为 RECOVERY_REQUIRED，记录失败原因和尝试次数。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int moveToRecoveryUpdate3(@Param("completedAt") Timestamp completedAt, @Param("errorCode") String errorCode, @Param("maxAutomaticSends") int maxAutomaticSends, @Param("completedAt2") Timestamp completedAt2, @Param("completedAt3") Timestamp completedAt3, @Param("tenantId") byte[] tenantId, @Param("aggregateId") byte[] aggregateId, @Param("lockVersion") long lockVersion);
    /**
     * 按租户、消息 ID、发送次数、令牌和版本读取仍处于 PUBLISHING 的消息正文，确认当前任务持有发送权。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<ClaimedOutbox> findClaimedSelect(@Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("attemptCount") int attemptCount, @Param("claimToken") String claimToken, @Param("lockVersion") long lockVersion);
    /**
     * 为耗尽自动发送次数的启动消息创建 OPEN 恢复记录，保留业务意图及安全错误摘要。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int ensureRecoveryUpdate(@Param("tenantId") byte[] tenantId, @Param("recoveryId") byte[] recoveryId, @Param("outboxId") byte[] outboxId, @Param("businessIntentKey") byte[] businessIntentKey, @Param("maxAutomaticSends") int maxAutomaticSends, @Param("errorCode") String errorCode, @Param("safeErrorSummary") String safeErrorSummary, @Param("completedAt") Timestamp completedAt, @Param("completedAt2") Timestamp completedAt2, @Param("completedAt3") Timestamp completedAt3);
    /**
     * 按租户和 Outbox 来源读取恢复记录，供核验业务意图与既有恢复状态。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<RecoveryState> findRecoverySelect(@Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId);
}
