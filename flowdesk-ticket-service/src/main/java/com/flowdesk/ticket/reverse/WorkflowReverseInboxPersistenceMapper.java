package com.flowdesk.ticket.reverse;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.sql.Timestamp;
import com.flowdesk.ticket.model.reverse.Aggregate;
import com.flowdesk.ticket.model.reverse.Audit;
import com.flowdesk.ticket.model.reverse.ClockIdentity;
import com.flowdesk.ticket.model.reverse.Inbox;
import com.flowdesk.ticket.model.reverse.RecoveryIdentity;
import com.flowdesk.ticket.model.reverse.RecoveryState;
import com.flowdesk.ticket.model.reverse.Timeline;

/**
 * 工作流回传 Inbox 接口，处理重复消息、流程关联、审批结果落库及恢复状态。
 * 租户边界与事务组织由调用方负责；本接口执行对应 SQL 并返回类型化结果。
 */
@Mapper
public interface WorkflowReverseInboxPersistenceMapper {
    /**
     * 按租户、生产方和消息 ID 查询重复 Inbox，供同一消息的重复投递识别。
     * lock 为 true 时附加 FOR UPDATE，行锁需在事务内使用。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<Inbox> findDuplicateSelect(@Param("tenantId") byte[] tenantId, @Param("producer") String producer, @Param("messageId") byte[] messageId, @Param("lock") boolean lock);
    /**
     * 按租户、生产方、契约和业务意图键查询 Inbox，识别消息 ID 不同但业务意图相同的投递。
     * lock 为 true 时附加 FOR UPDATE，行锁需在事务内使用。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<Inbox> findDuplicateSelect2(@Param("tenantId") byte[] tenantId, @Param("producer") String producer, @Param("contractRef") String contractRef, @Param("businessIntentKey") byte[] businessIntentKey, @Param("lock") boolean lock);
    /**
     * 锁定联查工单、流程启动关联、服务目录和 SLA 策略，供回传消息在同一事务中校验并落库。
     * 查询使用 FOR UPDATE 行锁，应在调用方事务内执行。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<Aggregate> findAggregateSelect(@Param("tenantId") byte[] tenantId, @Param("ticketId") byte[] ticketId);
    /**
     * 按关联版本将 REQUESTED 或 RECOVERY_REQUIRED 的流程关联置为 ESTABLISHED，并清除恢复原因。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int establishLinkUpdate(@Param("workflowInstanceId") byte[] workflowInstanceId, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("tenantId") byte[] tenantId, @Param("ticketId") byte[] ticketId, @Param("linkLockVersion") long linkLockVersion);
    /**
     * 锁定查询指定工单对应的工作流启动 Outbox 恢复项，供收到流程回传后关闭。
     * 查询使用 FOR UPDATE 行锁，应在调用方事务内执行。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<RecoveryState> resolveStartPublicationRecoverySelect(@Param("tenantId") byte[] tenantId, @Param("ticketId") byte[] ticketId);
    /**
     * 按读取到的状态及版本完成启动消息恢复项，清除重试时间。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int resolveStartPublicationRecoveryUpdate2(@Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("tenantId") byte[] tenantId, @Param("recoveryId") byte[] recoveryId, @Param("state") String state, @Param("lockVersion") long lockVersion);
    /**
     * 按工单版本将待审批工单转为 PENDING_ASSIGNMENT，写入首次可处理时间并保证更新时间递增。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int finalizeTicketUpdate(@Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("now3") Timestamp now3, @Param("tenantId") byte[] tenantId, @Param("ticketId") byte[] ticketId, @Param("ticketLockVersion") long ticketLockVersion);
    /**
     * 按工单版本将待审批工单转为 APPROVAL_REJECTED，设置终态时间并清除首次可处理时间。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int finalizeTicketUpdate2(@Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("now3") Timestamp now3, @Param("tenantId") byte[] tenantId, @Param("ticketId") byte[] ticketId, @Param("ticketLockVersion") long ticketLockVersion);
    /**
     * 检查指定工单和类型是否已有 SLA 时钟，供审批通过后避免重复初始化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<ClockIdentity> insertClockSelect(@Param("tenantId") byte[] tenantId, @Param("ticketId") byte[] ticketId, @Param("type") String type);
    /**
     * 审批通过后创建 RUNNING SLA 时钟，保存策略时限、提醒时间和截止时间快照。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int insertClockUpdate2(@Param("tenantId") byte[] tenantId, @Param("clockId") byte[] clockId, @Param("ticketId") byte[] ticketId, @Param("slaPolicyId") byte[] slaPolicyId, @Param("type") String type, @Param("started") Timestamp started, @Param("deadline") Timestamp deadline, @Param("reminderAt") Timestamp reminderAt, @Param("duration") Long duration, @Param("reminder") Long reminder, @Param("recordedAt") Timestamp recordedAt, @Param("recordedAt2") Timestamp recordedAt2);
    /**
     * 按工单和时钟类型回查既有时钟，供插入冲突后校验策略及计时信息。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<ClockIdentity> insertClockSelect3(@Param("tenantId") byte[] tenantId, @Param("ticketId") byte[] ticketId, @Param("type") String type);
    /**
     * 写入关联工作流实例的工单时间线，保留副作用键、业务发生时间及消息链路。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int insertTimelineUpdate(@Param("tenantId") byte[] tenantId, @Param("timelineId") byte[] timelineId, @Param("ticketId") byte[] ticketId, @Param("effectKey") byte[] effectKey, @Param("event") String event, @Param("summary") String summary, @Param("workflowInstanceId") byte[] workflowInstanceId, @Param("businessOccurredAt") Timestamp businessOccurredAt, @Param("correlationId") byte[] correlationId, @Param("causationId") byte[] causationId, @Param("recordedAt") Timestamp recordedAt);
    /**
     * 按工单和副作用键回查时间线，供审批结果重复投递时核验既有业务效果。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<Timeline> findTimelineSelect(@Param("tenantId") byte[] tenantId, @Param("ticketId") byte[] ticketId, @Param("effectKey") byte[] effectKey);
    /**
     * 保存工作流回传消息原文、摘要、契约和业务处理结果，初始化 Inbox 版本。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int insertInboxUpdate(@Param("tenantId") byte[] tenantId, @Param("stableInboxId") byte[] stableInboxId, @Param("producer") String producer, @Param("messageId") byte[] messageId, @Param("businessIntentKey") byte[] businessIntentKey, @Param("contractRef") String contractRef, @Param("version") int version, @Param("bodyBytes") byte[] bodyBytes, @Param("bodySha256") byte[] bodySha256, @Param("correlationId") byte[] correlationId, @Param("causationId") byte[] causationId, @Param("envelopeOccurredAt") Timestamp envelopeOccurredAt, @Param("status") String status, @Param("resultType") String resultType, @Param("resultId") byte[] resultId, @Param("attempts") int attempts, @Param("error") String error, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("processedAt") Timestamp processedAt, @Param("now3") Timestamp now3, @Param("now4") Timestamp now4);
    /**
     * 按预期版本将恢复中的 Inbox 置为 PROCESSED，绑定工单结果并记录处理时间。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int completeRecoveredInboxUpdate(@Param("ticketId") byte[] ticketId, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("now3") Timestamp now3, @Param("tenantId") byte[] tenantId, @Param("inboxId") byte[] inboxId, @Param("lockVersion") long lockVersion);
    /**
     * 按预期版本将恢复中的 Inbox 置为 REJECTED，记录拒绝码并递增尝试次数。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int rejectRecoveredInboxUpdate(@Param("code") String code, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("now3") Timestamp now3, @Param("tenantId") byte[] tenantId, @Param("inboxId") byte[] inboxId, @Param("lockVersion") long lockVersion);
    /**
     * 为失败的工作流回传 Inbox 创建 OPEN 恢复记录，保存业务意图、次数及错误摘要。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int insertRecoveryUpdate(@Param("tenantId") byte[] tenantId, @Param("stableRecoveryId") byte[] stableRecoveryId, @Param("stableInboxId") byte[] stableInboxId, @Param("businessIntentKey") byte[] businessIntentKey, @Param("deliveryAttempts") int deliveryAttempts, @Param("code") String code, @Param("summary") String summary, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("now3") Timestamp now3);
    /**
     * 按 Inbox 来源回查恢复记录身份及错误信息，供重复写入时核验。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<RecoveryIdentity> insertRecoverySelect2(@Param("tenantId") byte[] tenantId, @Param("stableInboxId") byte[] stableInboxId);
    /**
     * 锁定读取 Inbox 对应恢复项的当前状态及版本。
     * 查询使用 FOR UPDATE 行锁，应在调用方事务内执行。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<RecoveryState> resolveRecoverySelect(@Param("tenantId") byte[] tenantId, @Param("inboxId") byte[] inboxId);
    /**
     * 按当前状态和版本将 Inbox 恢复项置为 RESOLVED，并清除重试时间。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int resolveRecoveryUpdate2(@Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("tenantId") byte[] tenantId, @Param("recoveryId") byte[] recoveryId, @Param("state") String state, @Param("lockVersion") long lockVersion);
    /**
     * 按租户和 Inbox ID 读取回传消息及处理结果，供重放与重复消费检查。
     * lock 为 true 时附加 FOR UPDATE，行锁需在事务内使用。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<Inbox> findInboxSelect(@Param("tenantId") byte[] tenantId, @Param("inboxId") byte[] inboxId, @Param("lock") boolean lock);
    /**
     * 保存工作流回传影响工单的服务操作审计，记录业务结果、错误及消息链路。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int insertAuditUpdate(@Param("tenantId") byte[] tenantId, @Param("auditId") byte[] auditId, @Param("action") String action, @Param("targetId") byte[] targetId, @Param("outcome") String outcome, @Param("reason") String reason, @Param("error") String error, @Param("correlationId") byte[] correlationId, @Param("causationId") byte[] causationId, @Param("now") Timestamp now);
    /**
     * 按租户和审计 ID 读取审计内容，供重复消息处理时核对既有操作。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<Audit> findAuditSelect(@Param("tenantId") byte[] tenantId, @Param("auditId") byte[] auditId);
}
