package com.flowdesk.ticket.incident;

import com.flowdesk.ticket.model.incident.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 业务能力契约；实现直接使用 MyBatis Mapper，保留调用方事务边界。 */
public interface IncidentInboxService {
    /**
     * 按消息身份与业务意图读取既有 Inbox，区分首次处理、重放和冲突。
     */
    Duplicate duplicate(AcceptedIncidentMessage message, boolean lock);

    /**
     * 读取租户告警建单策略，供创建工单与 SLA 时钟使用。
     */
    Policy incidentPolicy(UUID tenantId);

    /**
     * 读取告警与工单的权威映射，防止同一告警重复建单。
     */
    Mapping mapping(UUID tenantId, UUID incidentId);

    /**
     * 在事务中分配当前租户业务日期下的下一个工单序号。
     */
    long nextSequence(UUID tenantId, LocalDate businessDate, Instant now);

    /**
     * 保存告警对应的工单事实，使用稳定身份支持重复投递去重。
     */
    void insertTicket(AcceptedIncidentMessage message, UUID ignoredTicketId,
                             String ticketNo, Instant now);

    /**
     * 写入告警与工单的权威关联，保持后续恢复定位到同一工单。
     */
    void insertMapping(AcceptedIncidentMessage message, UUID ignoredTicketId,
                              Instant now);

    /**
     * 记录工单业务时间线，使用稳定副作用键防止重复消费重复追加。
     */
    void insertTimeline(AcceptedIncidentMessage message, UUID ticketId,
                               byte[] effectKey, String eventType, String summary,
                               Instant occurredAt);

    /**
     * 按策略快照创建 SLA 时钟，使用来源身份派生稳定时钟标识。
     */
    UUID insertClock(AcceptedIncidentMessage message, UUID ticketId, Policy policy,
                            String clockType, long duration, Long reminder, Instant now);

    /**
     * 保存告警建单结果发件箱，重复执行复用相同发布身份与内容。
     */
    void insertOutboxEstablished(AcceptedIncidentMessage message, UUID ticketId,
                                        UUID ignoredOutboxId, UUID ignoredMessageId,
                                        byte[] ignoredPublicationKey, byte[] ignoredIntent,
                                        byte[] body, byte[] hash, Instant now);

    /**
     * 保存业务操作审计，记录目标、主体、结果和请求链路。
     */
    void insertAudit(AcceptedIncidentMessage message, UUID targetId, String action,
                            String outcome, String reason, String error, Instant now);

    /**
     * 保存业务操作审计，记录目标、主体、结果和请求链路。
     */
    void insertAudit(AcceptedIncidentMessage message, UUID auditId, UUID targetId,
                            String action, String outcome, String reason, String error,
                            Instant now);

    /**
     * 保存成功处理的 Inbox 事实及结果关联，使重复投递直接收敛。
     */
    void insertProcessedInbox(AcceptedIncidentMessage message, UUID ignoredInbox,
                                     UUID result, Instant now);

    /**
     * 保存拒绝消息结果与错误码，避免毒消息反复执行业务。
     */
    void insertRejectedInbox(AcceptedIncidentMessage message, UUID ignoredInbox,
                                    String code, Instant now);

    /**
     * 保存待恢复 Inbox，保留原始消息供受控重放。
     */
    void insertRecoveryInbox(AcceptedIncidentMessage message, UUID ignoredInbox,
                                    String code, Instant now);

    /**
     * 恢复成功后条件更新 Inbox 为已处理，并关联最终结果。
     */
    void completeRecoveredInbox(AcceptedIncidentMessage message, Inbox inbox,
                                       UUID result, Instant now);

    /**
     * 将恢复中的 Inbox 收敛为已拒绝，保留不可继续处理的原因。
     */
    void rejectRecoveredInbox(AcceptedIncidentMessage message, Inbox inbox,
                                     String code, Instant now);

    /**
     * 保存消息恢复记录和来源关联，供查询与人工重放。
     */
    void insertRecovery(AcceptedIncidentMessage message, UUID ignoredInbox,
                               UUID ignoredRecovery, String code, Instant now);
}
