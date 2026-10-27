package com.flowdesk.ticket.incident;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.sql.Timestamp;
import com.flowdesk.ticket.model.incident.AuditIdentity;
import com.flowdesk.ticket.model.incident.ClockIdentity;
import com.flowdesk.ticket.model.incident.Inbox;
import com.flowdesk.ticket.model.incident.Mapping;
import com.flowdesk.ticket.model.incident.OutboxIdentity;
import com.flowdesk.ticket.model.incident.Policy;
import com.flowdesk.ticket.model.incident.RecoveryIdentity;
import com.flowdesk.ticket.model.incident.RecoveryState;
import com.flowdesk.ticket.model.incident.TicketIdentity;
import com.flowdesk.ticket.model.incident.TimelineIdentity;

/**
 * 事件建单 Inbox 持久化接口，覆盖重复识别、事件与工单映射、SLA 初始化及恢复记录。
 * 租户边界与事务组织由调用方负责；本接口执行对应 SQL 并返回类型化结果。
 */
@Mapper
public interface IncidentInboxPersistenceMapper {
    /**
     * 按租户、生产方和消息 ID 查询重复 Inbox，供同一消息的重复投递识别。
     * lock 为 true 时附加 FOR UPDATE，行锁需在事务内使用。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<Inbox> duplicateSelect(@Param("tenantId") byte[] tenantId, @Param("producer") String producer, @Param("messageId") byte[] messageId, @Param("lock") boolean lock);
    /**
     * 按租户、生产方、契约和业务意图键查询 Inbox，识别消息 ID 不同但业务意图相同的投递。
     * lock 为 true 时附加 FOR UPDATE，行锁需在事务内使用。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<Inbox> duplicateSelect2(@Param("tenantId") byte[] tenantId, @Param("producer") String producer, @Param("contractRef") String contractRef, @Param("businessIntentKey") byte[] businessIntentKey, @Param("lock") boolean lock);
    /**
     * 锁定读取租户中已启用且标记为事件默认的 SLA 策略及提醒时限。
     * 查询使用 FOR UPDATE 行锁，应在调用方事务内执行。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<Policy> incidentPolicySelect(@Param("tenantId") byte[] tenantId);
    /**
     * 联查事件到工单的映射及工单来源、状态和创建意图，供确认事件建单结果。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<Mapping> mappingSelect(@Param("tenantId") byte[] tenantId, @Param("incidentId") byte[] incidentId);
    /**
     * 按租户和业务日期初始化或原子递增工单编号计数器。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int nextSequenceUpdate(@Param("tenantId") byte[] tenantId, @Param("businessDate") java.sql.Date businessDate, @Param("now") Timestamp now);
    /**
     * 锁定读取当日编号计数器，供事件建单在事务内取得序列。
     * 查询使用 FOR UPDATE 行锁，应在调用方事务内执行。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 当前租户和业务日期对应的序列值
     */
    Long nextSequenceSelect2(@Param("tenantId") byte[] tenantId, @Param("businessDate") java.sql.Date businessDate);
    /**
     * 创建 INCIDENT 来源、PENDING_ASSIGNMENT 状态的工单，初始化首次可处理时间和版本。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int insertTicketUpdate(@Param("tenantId") byte[] tenantId, @Param("ticketId") byte[] ticketId, @Param("ticketNo") String ticketNo, @Param("createTicketIntentKey") byte[] createTicketIntentKey, @Param("subject") String subject, @Param("description") String description, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("now3") Timestamp now3);
    /**
     * 按工单 ID 或创建意图键回查工单身份与业务字段，供插入冲突后的幂等一致性校验。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<TicketIdentity> insertTicketSelect2(@Param("tenantId") byte[] tenantId, @Param("ticketId") byte[] ticketId, @Param("createTicketIntentKey") byte[] createTicketIntentKey);
    /**
     * 保存事件与新建工单的一对关联及创建意图，记录建立映射时间。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int insertMappingUpdate(@Param("tenantId") byte[] tenantId, @Param("incidentId") byte[] incidentId, @Param("ticketId") byte[] ticketId, @Param("createTicketIntentKey") byte[] createTicketIntentKey, @Param("now") Timestamp now, @Param("now2") Timestamp now2);
    /**
     * 写入关联事件的工单时间线，以副作用键标识本次业务效果并保留消息链路。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int insertTimelineUpdate(@Param("tenantId") byte[] tenantId, @Param("timelineId") byte[] timelineId, @Param("ticketId") byte[] ticketId, @Param("effect") byte[] effect, @Param("eventType") String eventType, @Param("summary") String summary, @Param("incidentId") byte[] incidentId, @Param("occurredAt") Timestamp occurredAt, @Param("correlationId") byte[] correlationId, @Param("messageId") byte[] messageId, @Param("occurredAt2") Timestamp occurredAt2);
    /**
     * 按工单和副作用键回查时间线，供重复写入时核对既有事件内容。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<TimelineIdentity> insertTimelineSelect2(@Param("tenantId") byte[] tenantId, @Param("ticketId") byte[] ticketId, @Param("effect") byte[] effect);
    /**
     * 按事件默认 SLA 策略创建 RUNNING 时钟，保存截止时间、提醒时间及策略时限快照。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int insertClockUpdate(@Param("tenantId") byte[] tenantId, @Param("clockId") byte[] clockId, @Param("ticketId") byte[] ticketId, @Param("policyId") byte[] policyId, @Param("clockType") String clockType, @Param("now") Timestamp now, @Param("deadline") Timestamp deadline, @Param("reminderDue") Timestamp reminderDue, @Param("duration") long duration, @Param("reminder") Long reminder, @Param("now2") Timestamp now2, @Param("now3") Timestamp now3);
    /**
     * 按工单和时钟类型回查已建 SLA 时钟，供核对重复初始化的策略快照。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<ClockIdentity> insertClockSelect2(@Param("tenantId") byte[] tenantId, @Param("ticketId") byte[] ticketId, @Param("clockType") String clockType);
    /**
     * 保存事件建单成功结果的 PENDING Outbox，保留发布键、业务意图和消息正文摘要。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int insertOutboxEstablishedUpdate(@Param("tenantId") byte[] tenantId, @Param("outboxId") byte[] outboxId, @Param("messageId") byte[] messageId, @Param("publication") byte[] publication, @Param("intent") byte[] intent, @Param("incidentId") byte[] incidentId, @Param("establishedContractRef") String establishedContractRef, @Param("body") byte[] body, @Param("hash") byte[] hash, @Param("correlationId") byte[] correlationId, @Param("messageId2") byte[] messageId2, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("now3") Timestamp now3);
    /**
     * 按发布键回查事件结果 Outbox 的身份、契约及正文，供幂等一致性核对。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<OutboxIdentity> insertOutboxEstablishedSelect2(@Param("tenantId") byte[] tenantId, @Param("publication") byte[] publication);
    /**
     * 记录事件建单过程中由服务主体执行的工单审计及来源消息链路。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int insertAuditUpdate(@Param("tenantId") byte[] tenantId, @Param("auditId") byte[] auditId, @Param("action") String action, @Param("targetId") byte[] targetId, @Param("outcome") String outcome, @Param("reason") String reason, @Param("error") String error, @Param("correlationId") byte[] correlationId, @Param("messageId") byte[] messageId, @Param("now") Timestamp now, @Param("now2") Timestamp now2);
    /**
     * 按租户和审计 ID 回查操作内容，供重复审计写入的一致性核验。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<AuditIdentity> insertAuditSelect2(@Param("tenantId") byte[] tenantId, @Param("auditId") byte[] auditId);
    /**
     * 保存事件消息原文、摘要、业务意图和处理结果，初始化 Inbox 锁版本。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int insertInboxUpdate(@Param("tenantId") byte[] tenantId, @Param("inboxId") byte[] inboxId, @Param("producer") String producer, @Param("messageId") byte[] messageId, @Param("businessIntentKey") byte[] businessIntentKey, @Param("contractRef") String contractRef, @Param("bodySha256") byte[] bodySha256, @Param("bodyBytes") byte[] bodyBytes, @Param("correlationId") byte[] correlationId, @Param("causationId") byte[] causationId, @Param("envelopeOccurredAt") Timestamp envelopeOccurredAt, @Param("status") String status, @Param("resultType") String resultType, @Param("result") byte[] result, @Param("attempts") int attempts, @Param("error") String error, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("processedAt") Timestamp processedAt, @Param("now3") Timestamp now3, @Param("now4") Timestamp now4);
    /**
     * 按 Inbox ID 回查事件消息的身份、处理状态及结果。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<Inbox> insertInboxSelect2(@Param("tenantId") byte[] tenantId, @Param("inboxId") byte[] inboxId);
    /**
     * 将 RECOVERY_REQUIRED 的 Inbox 置为 PROCESSED，写入工单结果并递增处理次数和锁版本。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int completeRecoveredInboxUpdate(@Param("result") byte[] result, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("now3") Timestamp now3, @Param("tenantId") byte[] tenantId, @Param("inboxId") byte[] inboxId, @Param("lockVersion") long lockVersion);
    /**
     * 将 RECOVERY_REQUIRED 的 Inbox 置为 REJECTED，记录错误码及处理完成时间。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int rejectRecoveredInboxUpdate(@Param("code") String code, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("now3") Timestamp now3, @Param("tenantId") byte[] tenantId, @Param("inboxId") byte[] inboxId, @Param("lockVersion") long lockVersion);
    /**
     * 为处理失败的事件 Inbox 创建 OPEN 恢复记录，保存尝试次数和错误摘要。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int insertRecoveryUpdate(@Param("tenantId") byte[] tenantId, @Param("recoveryId") byte[] recoveryId, @Param("inboxId") byte[] inboxId, @Param("businessIntentKey") byte[] businessIntentKey, @Param("deliveryAttempts") int deliveryAttempts, @Param("code") String code, @Param("summary") String summary, @Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("now3") Timestamp now3);
    /**
     * 按 Inbox 来源回查恢复记录身份及错误信息，供恢复记录重复插入时校验。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<RecoveryIdentity> insertRecoverySelect2(@Param("tenantId") byte[] tenantId, @Param("inboxId") byte[] inboxId);
    /**
     * 锁定查询指定 Inbox 的恢复记录状态及版本，供完成处理后关闭恢复项。
     * 查询使用 FOR UPDATE 行锁，应在调用方事务内执行。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<RecoveryState> resolveRecoverySelect(@Param("tenantId") byte[] tenantId, @Param("inboxId") byte[] inboxId);
    /**
     * 按读取到的状态和版本将恢复记录置为 RESOLVED，清除后续重试时间。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 数据库报告的受影响行数
     */
    int resolveRecoveryUpdate2(@Param("now") Timestamp now, @Param("now2") Timestamp now2, @Param("tenantId") byte[] tenantId, @Param("recoveryId") byte[] recoveryId, @Param("state") String state, @Param("lockVersion") long lockVersion);
    /**
     * 按租户和 Inbox ID 读取完整事件消息及处理状态，供重放或结果检查。
     * lock 为 true 时附加 FOR UPDATE，行锁需在事务内使用。
     *
     * @param tenantId 租户标识的数据库二进制值，用于限定数据所属租户
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<Inbox> findInboxSelect(@Param("tenantId") byte[] tenantId, @Param("inboxId") byte[] inboxId, @Param("lock") boolean lock);
}
