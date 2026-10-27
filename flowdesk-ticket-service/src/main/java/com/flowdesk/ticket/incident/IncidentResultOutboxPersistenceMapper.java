package com.flowdesk.ticket.incident;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.sql.Timestamp;
import com.flowdesk.ticket.model.incident.result.AuditState;
import com.flowdesk.ticket.model.incident.result.Candidate;
import com.flowdesk.ticket.model.incident.result.Claimed;
import com.flowdesk.ticket.model.incident.result.RecoveryState;

/**
 * 事件建单结果 Outbox 接口，负责候选扫描、发送认领、结果确认及失败恢复。
 * 租户边界与事务组织由调用方负责；本接口执行对应 SQL 并返回类型化结果。
 */
@Mapper
public interface IncidentResultOutboxPersistenceMapper {
    /**
     * 跨租户扫描契约匹配的到期 PENDING 或租约过期 PUBLISHING 消息，按待处理时间、租户和 ID 限量排序。
     *
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<Candidate> candidatesSelect(@Param("establishedContractRef") String establishedContractRef, @Param("version") int version, @Param("maxAttempts") int maxAttempts, @Param("now") Timestamp now, @Param("cutoff") Timestamp cutoff, @Param("outboxMaxClaims") int outboxMaxClaims);
    /**
     * 认领已到期的 PENDING 消息，写入发送令牌和租约时间，并递增发送次数与锁版本。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int claimUpdate(@Param("token") String token, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("attemptCount") int attemptCount, @Param("maxAttempts") int maxAttempts, @Param("lockVersion") long lockVersion, @Param("now3") Timestamp now3);
    /**
     * 接管租约已过期的 PUBLISHING 消息，核对原令牌、次数和版本后重新认领并增加发送次数。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int claimUpdate2(@Param("token") String token, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("attemptCount") int attemptCount, @Param("maxAttempts") int maxAttempts, @Param("lockedBy") String lockedBy, @Param("lockVersion") long lockVersion, @Param("cutoff") Timestamp cutoff);
    /**
     * 认领已达自动发送上限的到期 PENDING 消息，供进入恢复流程；不再增加发送次数。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int claimRecoveryUpdate(@Param("token") String token, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("maxAttempts") int maxAttempts, @Param("lockVersion") long lockVersion, @Param("now3") Timestamp now3);
    /**
     * 接管已达发送上限且租约过期的 PUBLISHING 消息，供恢复处理；不再增加发送次数。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int claimRecoveryUpdate2(@Param("token") String token, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("maxAttempts") int maxAttempts, @Param("lockedBy") String lockedBy, @Param("lockVersion") long lockVersion, @Param("cutoff") Timestamp cutoff);
    /**
     * 按租户、消息 ID、发送次数、令牌和版本读取仍处于 PUBLISHING 的消息正文，确认当前任务持有发送权。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<Claimed> claimedSelect(@Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("attempt") int attempt, @Param("token") String token, @Param("version") long version);
    /**
     * 在发送令牌、次数、版本和有效租约均匹配时确认 PUBLISHED，记录 Broker 确认时间并清除租约。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int publishedUpdate(@Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("attemptCount") int attemptCount, @Param("token") String token, @Param("lockVersion") long lockVersion, @Param("cutoff") Timestamp cutoff);
    /**
     * 按预期锁版本将 REPLAYING 恢复项置为 RESOLVED，记录完成时间并清除重试时间。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int resolveReplayRecoveryUpdate(@Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("tenantId") byte[] tenantId, @Param("recoveryId") byte[] recoveryId, @Param("lockVersion") long lockVersion);
    /**
     * 在当前发送租约仍有效时退回 PENDING，保存错误码及下次尝试时间并释放租约。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int pendingUpdate(@Param("next") Timestamp next, @Param("safeCode") String safeCode, @Param("now") Timestamp now, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("attemptCount") int attemptCount, @Param("token") String token, @Param("lockVersion") long lockVersion, @Param("cutoff") Timestamp cutoff);
    /**
     * 在当前发送租约有效且次数达到上限时，将事件结果消息转为 RECOVERY_REQUIRED。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int recoverUpdate(@Param("now") Timestamp now, @Param("safeCode") String safeCode, @Param("now2") Timestamp now2, @Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("maxAttempts") int maxAttempts, @Param("token") String token, @Param("lockVersion") long lockVersion, @Param("cutoff") Timestamp cutoff);
    /**
     * 按预期版本将重放失败的事件结果恢复项重新置为 OPEN，更新错误摘要。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int ensureRecoveryUpdate(@Param("code") String code, @Param("summary") String summary, @Param("now") Timestamp now, @Param("tenantId") byte[] tenantId, @Param("recoveryId") byte[] recoveryId, @Param("lockVersion") long lockVersion);
    /**
     * 为事件结果 Outbox 创建 OPEN 恢复记录，保留业务意图、发送上限及失败原因。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int ensureRecoveryUpdate2(@Param("tenantId") byte[] tenantId, @Param("recoveryId") byte[] recoveryId, @Param("outboxId") byte[] outboxId, @Param("intent") byte[] intent, @Param("maxAttempts") int maxAttempts, @Param("code") String code, @Param("summary") String summary, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("now3") Timestamp now3);
    /**
     * 写入事件结果消息进入恢复的失败审计，审计 ID 由调用方用于重复写入识别。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int insertAuditOnceUpdate(@Param("tenantId") byte[] tenantId, @Param("auditId") byte[] auditId, @Param("outboxId") byte[] outboxId, @Param("summary") String summary, @Param("code") String code, @Param("correlationId") byte[] correlationId, @Param("causationId") byte[] causationId, @Param("now") Timestamp now);
    /**
     * 按 Outbox 来源读取事件结果恢复记录，供重复记录校验及重放状态处理。
     * lock 为 true 时附加 FOR UPDATE，行锁需在事务内使用。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<RecoveryState> findRecoverySelect(@Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("lock") boolean lock);
    /**
     * 读取既有事件结果恢复审计，供核验重复操作的动作、目标和链路。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<AuditState> findAuditSelect(@Param("tenantId") byte[] tenantId, @Param("auditId") byte[] auditId);
}
