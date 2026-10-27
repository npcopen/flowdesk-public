package com.flowdesk.ticket.notification;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.sql.Timestamp;
import com.flowdesk.ticket.model.notification.Candidate;
import com.flowdesk.ticket.model.notification.Claimed;
import com.flowdesk.ticket.model.notification.IntentIdentity;
import com.flowdesk.ticket.model.notification.Recovery;

/**
 * 工单通知 Outbox 接口，保存通知意图并维护发送租约、重试及人工恢复状态。
 * 租户边界与事务组织由调用方负责；本接口执行对应 SQL 并返回类型化结果。
 */
@Mapper
public interface TicketNotificationOutboxPersistenceMapper {
    /**
     * 保存通知请求的 Outbox 意图，固定聚合类型为 NOTIFICATION_REQUEST，初始等待发送。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int insertIntentUpdate(@Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("messageId") byte[] messageId, @Param("publicationKey") byte[] publicationKey, @Param("businessIntentKey") byte[] businessIntentKey, @Param("sourceRefId") byte[] sourceRefId, @Param("contractRef") String contractRef, @Param("version") int version, @Param("bodyBytes") byte[] bodyBytes, @Param("bodySha256") byte[] bodySha256, @Param("correlationId") byte[] correlationId, @Param("causationId") byte[] causationId, @Param("occurredAt") Timestamp occurredAt, @Param("occurredAt2") Timestamp occurredAt2, @Param("occurredAt3") Timestamp occurredAt3);
    /**
     * 按租户和发布键回查通知意图的身份、契约与正文，供重复发布的一致性校验。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<IntentIdentity> insertIntentSelect2(@Param("tenantId") byte[] tenantId, @Param("publicationKey") byte[] publicationKey);
    /**
     * 跨租户扫描契约匹配的到期 PENDING 或租约过期 PUBLISHING 消息，按待处理时间、租户和 ID 限量排序。
     *
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<Candidate> findCandidatesSelect(@Param("contractRef") String contractRef, @Param("version") int version, @Param("outboxMaxAttempts") int outboxMaxAttempts, @Param("now") Timestamp now, @Param("leaseCutoff") Timestamp leaseCutoff, @Param("limit") int limit);
    /**
     * 认领已到期的 PENDING 消息，写入发送令牌和租约时间，并递增发送次数与锁版本。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int claimForSendUpdate(@Param("token") String token, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("attemptCount") int attemptCount, @Param("outboxMaxAttempts") int outboxMaxAttempts, @Param("lockVersion") long lockVersion, @Param("now3") Timestamp now3);
    /**
     * 接管租约已过期的 PUBLISHING 消息，核对原令牌、次数和版本后重新认领并增加发送次数。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int claimForSendUpdate2(@Param("token") String token, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("attemptCount") int attemptCount, @Param("outboxMaxAttempts") int outboxMaxAttempts, @Param("lockedBy") String lockedBy, @Param("lockVersion") long lockVersion, @Param("leaseCutoff") Timestamp leaseCutoff);
    /**
     * 认领已达自动发送上限的到期 PENDING 消息，供进入恢复流程；不再增加发送次数。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int claimForRecoveryUpdate(@Param("token") String token, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("outboxMaxAttempts") int outboxMaxAttempts, @Param("lockVersion") long lockVersion, @Param("now3") Timestamp now3);
    /**
     * 接管已达发送上限且租约过期的 PUBLISHING 消息，供恢复处理；不再增加发送次数。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int claimForRecoveryUpdate2(@Param("token") String token, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("outboxMaxAttempts") int outboxMaxAttempts, @Param("lockedBy") String lockedBy, @Param("lockVersion") long lockVersion, @Param("leaseCutoff") Timestamp leaseCutoff);
    /**
     * 在发送令牌、次数、版本和有效租约均匹配时确认 PUBLISHED，记录 Broker 确认时间并清除租约。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int markPublishedUpdate(@Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("attemptCount") int attemptCount, @Param("claimToken") String claimToken, @Param("lockVersion") long lockVersion, @Param("leaseCutoff") Timestamp leaseCutoff);
    /**
     * 在当前发送租约仍有效时退回 PENDING，保存错误码及下次尝试时间并释放租约。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int markPendingUpdate(@Param("next") Timestamp next, @Param("errorCode") String errorCode, @Param("now") Timestamp now, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("attemptCount") int attemptCount, @Param("claimToken") String claimToken, @Param("lockVersion") long lockVersion, @Param("leaseCutoff") Timestamp leaseCutoff);
    /**
     * 在当前发送租约有效时将通知消息置为 RECOVERY_REQUIRED，记录失败码并释放租约。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int moveAndRecordUpdate(@Param("now") Timestamp now, @Param("code") String code, @Param("now2") Timestamp now2, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("attemptCount") int attemptCount, @Param("claimToken") String claimToken, @Param("lockVersion") long lockVersion, @Param("leaseCutoff") Timestamp leaseCutoff);
    /**
     * 写入通知 Outbox 进入恢复所需的失败审计，保留清理后的错误摘要及链路信息。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int moveAndRecordUpdate2(@Param("tenantId") byte[] tenantId, @Param("randomUUID") byte[] randomUUID, @Param("literal") String literal, @Param("outboxId") byte[] outboxId, @Param("clean") String clean, @Param("code") String code, @Param("correlationId") byte[] correlationId, @Param("causationId") byte[] causationId, @Param("now") Timestamp now);
    /**
     * 为通知 Outbox 创建 OPEN 恢复记录，保存业务意图、尝试次数和失败原因。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int ensureRecoveryUpdate(@Param("tenantId") byte[] tenantId, @Param("recoveryId") byte[] recoveryId, @Param("outboxId") byte[] outboxId, @Param("businessIntentKey") byte[] businessIntentKey, @Param("attemptCount") int attemptCount, @Param("code") String code, @Param("summary") String summary, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("now3") Timestamp now3);
    /**
     * 按租户和 Outbox 来源读取恢复记录，供核验业务意图与既有恢复状态。
     * 查询使用 FOR UPDATE 行锁，应在调用方事务内执行。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<Recovery> findRecoverySelect(@Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId);
    /**
     * 按预期版本将重放中的恢复项重新置为 OPEN，更新失败信息及尝试次数。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int reopenRecoveryUpdate(@Param("attemptCount") int attemptCount, @Param("code") String code, @Param("summary") String summary, @Param("now") Timestamp now, @Param("tenantId") byte[] tenantId, @Param("recoveryId") byte[] recoveryId, @Param("lockVersion") long lockVersion);
    /**
     * 通知重放发布成功后，将同源且处于 REPLAYING 的 Outbox 恢复项置为 RESOLVED。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int resolveReplayUpdate(@Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId);
    /**
     * 按租户、消息 ID、发送次数、令牌和版本读取仍处于 PUBLISHING 的消息正文，确认当前任务持有发送权。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<Claimed> findClaimedSelect(@Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("attempts") int attempts, @Param("token") String token, @Param("version") long version);
}
