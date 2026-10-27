package com.flowdesk.ticket.reverse;

import com.flowdesk.ticket.model.reverse.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 业务能力契约；实现直接使用 MyBatis Mapper，保留调用方事务边界。 */
public interface WorkflowReverseInboxService {
    /**
     * 读取既有接收事实，供消费前判断重复投递及内容冲突。
     */
    Duplicate findDuplicate(AcceptedWorkflowReverse message, boolean lock);

    /**
     * 读取工单、启动关联及相关事实，供反向消息在事务中统一校验。
     */
    Aggregate findAggregate(AcceptedWorkflowReverse message);

    /**
     * 建立工单与工作流实例关联，已有绑定必须与原启动意图一致。
     */
    void establishLink(AcceptedWorkflowReverse message, Aggregate aggregate, Instant now);

    /**
     * 按工作流终态条件推进工单，防止相反审批结论覆盖既定结果。
     */
    FinalizationResult finalizeTicket(AcceptedWorkflowReverse message,
                                             Aggregate aggregate, Instant now);

    /**
     * 从进入可处理状态的时点创建 SLA 时钟，重复消费复用稳定来源身份。
     */
    void insertSlaClocks(AcceptedWorkflowReverse message, Aggregate aggregate,
                                Instant startedAt, Instant recordedAt);

    /**
     * 从进入可处理状态的时点创建 SLA 时钟，重复消费复用稳定来源身份。
     */
    void insertSlaClocks(AcceptedWorkflowReverse message, Aggregate aggregate,
                                Instant recordedAt);

    /**
     * 记录工单业务时间线，使用稳定副作用键防止重复消费重复追加。
     */
    void insertTimeline(AcceptedWorkflowReverse message);

    /**
     * 记录工单业务时间线，使用稳定副作用键防止重复消费重复追加。
     */
    void insertTimeline(AcceptedWorkflowReverse message, Instant recordedAt);

    /**
     * 保存成功处理的 Inbox 事实及结果关联，使重复投递直接收敛。
     */
    void insertProcessedInbox(AcceptedWorkflowReverse message, UUID inboxId, Instant now);

    /**
     * 保存拒绝消息结果与错误码，避免毒消息反复执行业务。
     */
    void insertRejectedInbox(AcceptedWorkflowReverse message, UUID inboxId,
                                    String code, Instant now);

    /**
     * 保存待恢复 Inbox，保留原始消息供受控重放。
     */
    void insertRecoveryInbox(AcceptedWorkflowReverse message, UUID inboxId,
                                    String code, Instant now);

    /**
     * 恢复成功后条件更新 Inbox 为已处理，并关联最终结果。
     */
    boolean completeRecoveredInbox(AcceptedWorkflowReverse message, Inbox inbox, Instant now);

    /**
     * 将恢复中的 Inbox 收敛为已拒绝，保留不可继续处理的原因。
     */
    boolean rejectRecoveredInbox(AcceptedWorkflowReverse message, Inbox inbox,
                                        String code, Instant now);

    /**
     * 保存消息恢复记录和来源关联，供查询与人工重放。
     */
    void insertRecovery(AcceptedWorkflowReverse message, UUID inboxId, UUID recoveryId,
                               String code, String summary, Instant now);

    /**
     * 保存业务操作审计，记录目标、主体、结果和请求链路。
     */
    void insertAudit(AcceptedWorkflowReverse message, UUID targetId, String action,
                            String outcome, String reason, String error, Instant now);

    /**
     * 保存业务操作审计，记录目标、主体、结果和请求链路。
     */
    void insertAudit(AcceptedWorkflowReverse message, UUID auditId, UUID targetId,
                            String action, String outcome, String reason, String error,
                            Instant now);

    /**
     * 按稳定身份读取既有审计，供重复处理时核对业务内容。
     */
    Optional<Audit> findAudit(AcceptedWorkflowReverse message, UUID auditId);

    /**
     * 比较已有审计与预期业务内容，拒绝不同意图复用同一身份。
     */
    boolean sameAudit(Audit row, UUID auditId, UUID targetId, String action,
                             String outcome, String reason, String error,
                             AcceptedWorkflowReverse message);
}
