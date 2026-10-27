package com.flowdesk.ticket.reverse;

import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.ticket.notification.TicketNotificationIntent;
import com.flowdesk.ticket.notification.TicketNotificationOutboxWriter;
import com.flowdesk.ticket.service.IdempotencyCodec;
import com.flowdesk.ticket.sla.SlaIndexProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** 工单侧流程实例确认与审批终结入口，校验关联事实并在本地事务内收敛工单及副作用。 */
public final class WorkflowReverseConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowReverseConsumer.class);
    private final WorkflowReverseEnvelopeGuard guard;
    private final WorkflowReverseInboxService repository;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final TicketNotificationOutboxWriter notificationWriter;
    private final SlaIndexProjection slaIndexProjection;

    /**
     * 构造工作流反向结果组件并保存调用依赖。
     */
    WorkflowReverseConsumer(WorkflowReverseEnvelopeGuard guard,
                            WorkflowReverseInboxService repository,
                            PlatformTransactionManager transactionManager) {
        this(guard, repository, transactionManager, Clock.systemUTC(), null, null);
    }

    /**
     * 构造工作流反向结果组件并保存调用依赖；支持注入时钟以控制时间边界。
     */
    WorkflowReverseConsumer(WorkflowReverseEnvelopeGuard guard,
                            WorkflowReverseInboxService repository,
                            PlatformTransactionManager transactionManager,
                            Clock clock) {
        this(guard, repository, transactionManager, clock, null, null);
    }

    /**
     * 构造工作流反向结果组件并保存调用依赖；支持注入时钟以控制时间边界。
     */
    WorkflowReverseConsumer(WorkflowReverseEnvelopeGuard guard,
                            WorkflowReverseInboxService repository,
                            PlatformTransactionManager transactionManager,
                            Clock clock,
                            TicketNotificationOutboxWriter notificationWriter) {
        this(guard, repository, transactionManager, clock, notificationWriter, null);
    }

    /**
     * 构造工作流反向结果组件并保存调用依赖；支持注入时钟以控制时间边界。
     */
    WorkflowReverseConsumer(WorkflowReverseEnvelopeGuard guard,
                            WorkflowReverseInboxService repository,
                            PlatformTransactionManager transactionManager,
                            Clock clock,
                            TicketNotificationOutboxWriter notificationWriter,
                            SlaIndexProjection slaIndexProjection) {
        this.guard = guard;
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.notificationWriter = notificationWriter;
        this.slaIndexProjection = slaIndexProjection;
    }

    /** 先验证传输契约再建立可信租户上下文；数据库或事务暂时故障返回 RETRY。 */
    public WorkflowReverseConsumeResult consume(WorkflowReverseTransportMessage delivery) {
        final AcceptedWorkflowReverse message;
        try {
            message = guard.validateMain(delivery);
        }
        catch (WorkflowReverseContractViolation poison) {
            LOG.warn("Workflow reverse delivery ACKed by transport guard; code={}", poison.code());
            return WorkflowReverseConsumeResult.ack(poison.code());
        }
        TenantContext context = TenantContext.ofValidatedBrokerMessage(
                message.tenantId(), WorkflowReverseContract.PRODUCER, message.correlationId());
        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            try {
                WorkflowReverseConsumeResult result = transactions.execute(tx -> process(message));
                return Objects.requireNonNull(result);
            }
            catch (DataAccessException | TransactionException | IllegalStateException transientFailure) {
                LOG.warn("Workflow reverse delivery returned to Broker; dependency=DATABASE");
                return WorkflowReverseConsumeResult.retry("TICKET_DB_UNAVAILABLE");
            }
        }
    }

    /** 按消息身份去重，验证流程与工单关联后，统一提交业务变更、Inbox 终态和审计。 */
    private WorkflowReverseConsumeResult process(AcceptedWorkflowReverse message) {
        com.flowdesk.ticket.model.reverse.Duplicate duplicate = repository.findDuplicate(message, true);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (duplicate.kind() == com.flowdesk.ticket.model.reverse.DuplicateKind.TERMINAL) {
            // 终态 Inbox 不单独证明所有确定性业务锚点均已提交。
            // 对 PROCESSED 消息再次投递时补核时间线、SLA、审计及通知 Outbox；已拒绝的非法消息保持终态。
            if (duplicate.inbox() != null
                    && "PROCESSED".equals(duplicate.inbox().status())) {
                reconcileProcessedDuplicate(message, now);
            }
            return WorkflowReverseConsumeResult.ack("WORKFLOW_REVERSE_DUPLICATE");
        }
        if (duplicate.kind() == com.flowdesk.ticket.model.reverse.DuplicateKind.CONFLICT) {
            UUID target = duplicate.inbox().resultId() == null
                    ? message.ticketId() : duplicate.inbox().resultId();
            insertAuditOnce(message, target, "WORKFLOW_REVERSE_CONFLICT", "REJECTED",
                    "Message identity was reused with different content.",
                    duplicate.conflictCode(), now);
            return WorkflowReverseConsumeResult.ack(duplicate.conflictCode());
        }

        com.flowdesk.ticket.model.reverse.Aggregate aggregate = repository.findAggregate(message);
        String invalid = validateAggregate(message, aggregate, duplicate.kind() !=
                com.flowdesk.ticket.model.reverse.DuplicateKind.NONE);
        if (invalid != null) {
            if (duplicate.kind() == com.flowdesk.ticket.model.reverse.DuplicateKind.RECOVERABLE) {
                repository.rejectRecoveredInbox(message, duplicate.inbox(), invalid, now);
            }
            else {
                repository.insertRejectedInbox(message, inboxId(message), invalid, now);
            }
            insertAuditOnce(message, message.ticketId(), "WORKFLOW_REVERSE_REJECTED",
                    "REJECTED", "Reverse event references do not match Ticket facts.", invalid, now);
            return WorkflowReverseConsumeResult.ack(invalid);
        }

        repository.establishLink(message, aggregate, now);
        com.flowdesk.ticket.model.reverse.FinalizationResult finalization =
                com.flowdesk.ticket.model.reverse.FinalizationResult.APPLIED;
        if (message.binding().kind() == WorkflowReverseContract.Kind.APPROVAL_FINALIZED) {
            finalization = repository.finalizeTicket(message, aggregate, now);
            // 首次处理与恢复均通过确定性的副作用身份收敛。
            // 恢复可能看到工单已终结而副作用仍缺失，因此 ALREADY_APPLIED 也需逐一验证和补齐锚点。
            if ("PASSED".equals(message.outcome())) {
                // SLA 从工单侧接受可信终结事实时起算，不使用远端流程事件时间。
                // 幂等重入复用已持久化起点，避免迟到的重复消息推迟截止时间。
                Instant slaStartedAt = finalization
                        == com.flowdesk.ticket.model.reverse.FinalizationResult.APPLIED
                        ? now : aggregate.firstProcessableAt();
                repository.insertSlaClocks(message, aggregate, slaStartedAt, now);
                scheduleSlaIndexProjections(message);
            }
            repository.insertTimeline(message, now);
            writeRequesterNotification(message, aggregate, now);
        }
        if (duplicate.kind() == com.flowdesk.ticket.model.reverse.DuplicateKind.RECOVERABLE) {
            repository.completeRecoveredInbox(message, duplicate.inbox(), now);
        }
        else {
            repository.insertProcessedInbox(message, inboxId(message), now);
        }
        String action = message.binding().kind() == WorkflowReverseContract.Kind.INSTANCE_ESTABLISHED
                ? "WORKFLOW_INSTANCE_ESTABLISHED" : "WORKFLOW_APPROVAL_FINALIZED";
        insertAuditOnce(message, message.ticketId(), action, "SUCCEEDED", null, null, now);
        return WorkflowReverseConsumeResult.ack("WORKFLOW_REVERSE_PROCESSED");
    }

    /**
     * 核对已处理反向消息的关联事实，收敛状态而不重复业务副作用。
     */
    private void reconcileProcessedDuplicate(AcceptedWorkflowReverse message, Instant now) {
        com.flowdesk.ticket.model.reverse.Aggregate aggregate = repository.findAggregate(message);
        String invalid = validateAggregate(message, aggregate, true);
        if (invalid != null) {
            // 已处理来源必须匹配原工单事实；缺失或损坏时返回重试，交由修复而不制造新的 Inbox。
            throw new IllegalStateException("Processed Workflow reverse source cannot be reconciled: "
                    + invalid);
        }
        repository.establishLink(message, aggregate, now);
        if (message.binding().kind() == WorkflowReverseContract.Kind.APPROVAL_FINALIZED) {
            com.flowdesk.ticket.model.reverse.FinalizationResult finalization =
                    repository.finalizeTicket(message, aggregate, now);
            if ("PASSED".equals(message.outcome())) {
                Instant startedAt = finalization
                        == com.flowdesk.ticket.model.reverse.FinalizationResult.APPLIED
                        ? now : aggregate.firstProcessableAt();
                repository.insertSlaClocks(message, aggregate, startedAt, now);
                scheduleSlaIndexProjections(message);
            }
            repository.insertTimeline(message, now);
            writeRequesterNotification(message, aggregate, now);
        }
        String action = message.binding().kind() == WorkflowReverseContract.Kind.INSTANCE_ESTABLISHED
                ? "WORKFLOW_INSTANCE_ESTABLISHED" : "WORKFLOW_APPROVAL_FINALIZED";
        insertAuditOnce(message, message.ticketId(), action, "SUCCEEDED", null, null, now);
    }

    /** 审计 UUID 确定性派生；遇到并发重复插入时核对内容一致，不能只凭主键相同认定成功。 */
    private void insertAuditOnce(AcceptedWorkflowReverse message, UUID targetId,
                                 String action, String outcome, String reason,
                                 String errorCode, Instant now) {
        UUID auditId = WorkflowReverseIdentity.auditId(message, action, targetId,
                outcome, reason, errorCode);
        com.flowdesk.ticket.model.reverse.Audit existing = repository.findAudit(message, auditId)
                .orElse(null);
        if (existing != null) {
            if (!repository.sameAudit(existing, auditId, targetId, action, outcome,
                    reason, errorCode, message)) {
                throw new IllegalStateException("Workflow reverse audit identity/content conflict");
            }
            return;
        }
        try {
            repository.insertAudit(message, auditId, targetId, action, outcome,
                    reason, errorCode, now);
        }
        catch (org.springframework.dao.DuplicateKeyException duplicate) {
            com.flowdesk.ticket.model.reverse.Audit raced = repository.findAudit(message, auditId)
                    .orElseThrow(() -> duplicate);
            if (!repository.sameAudit(raced, auditId, targetId, action, outcome,
                    reason, errorCode, message)) {
                throw new IllegalStateException("Workflow reverse audit identity/content conflict", duplicate);
            }
        }
    }

    /**
     * 为申请人保存审批结果通知，重复消费沿用稳定发布身份。
     */
    private void writeRequesterNotification(AcceptedWorkflowReverse message,
                                             com.flowdesk.ticket.model.reverse.Aggregate aggregate,
                                             Instant now) {
        if (aggregate.requesterUserId() == null) {
            insertAuditOnce(message, message.ticketId(),
                    "APPROVAL_REQUESTER_NOTIFICATION_SKIPPED", "SUCCEEDED",
                    "Ticket has no concrete human requester.", "REQUESTER_NOT_FOUND", now);
            return;
        }
        if (notificationWriter == null) {
            return;
        }
        boolean autoPassed = "PASSED".equals(message.outcome())
                && Boolean.FALSE.equals(message.conditionResult());
        String kind = "PASSED".equals(message.outcome())
                ? (autoPassed ? TicketNotificationIntent.APPROVAL_AUTO_PASSED
                        : TicketNotificationIntent.APPROVAL_PASSED)
                : TicketNotificationIntent.APPROVAL_REJECTED;
        String title = "PASSED".equals(message.outcome())
                ? (autoPassed ? "工单已自动通过" : "审批已通过") : "审批已拒绝";
        String body = "PASSED".equals(message.outcome())
                ? (autoPassed ? "该工单不需要审批，已自动通过。" : "你提交的工单审批已通过。")
                : "你提交的工单审批未通过。";
        notificationWriter.write(message.tenantId(), TicketNotificationIntent.create(
                message.tenantId(), aggregate.requesterUserId(), kind,
                WorkflowReverseIdentity.finalizationEffectKey(message), "TICKET",
                message.ticketId(), title, body,
                message.correlationId(), message.messageId(), now));
    }

    /**
     * 为本次时钟安排提交后投影，使索引反映已落库事实。
     */
    private void scheduleSlaIndexProjections(AcceptedWorkflowReverse message) {
        if (slaIndexProjection == null) {
            return;
        }
        slaIndexProjection.schedule(message.tenantId(),
                WorkflowReverseIdentity.slaClockId(message.tenantId(), message.ticketId(),
                        "FIRST_RESPONSE"));
        slaIndexProjection.schedule(message.tenantId(),
                WorkflowReverseIdentity.slaClockId(message.tenantId(), message.ticketId(),
                        "RESOLUTION"));
    }

    /**
     * 核对工单、启动意图与实例关联，拒绝冲突消息推进工单。
     */
    private static String validateAggregate(AcceptedWorkflowReverse message,
                                            com.flowdesk.ticket.model.reverse.Aggregate aggregate,
                                            boolean allowPostState) {
        if (aggregate == null || aggregate.startIntentKey() == null
                || aggregate.linkStatus() == null || aggregate.requestedAt() == null) {
            return "TICKET_WORKFLOW_REFERENCE_MISMATCH";
        }
        if (!message.ticketId().equals(aggregate.ticketId())
                || !message.workflowDefinitionId().equals(aggregate.definitionId())
                || !message.workflowVersionId().equals(aggregate.versionId())
                || aggregate.versionNo() == null || message.versionNo() != aggregate.versionNo()
                || !MessageDigest.isEqual(message.startIntentKey(), aggregate.startIntentKey())) {
            return "TICKET_WORKFLOW_REFERENCE_MISMATCH";
        }
        if (!java.util.Set.of("REQUESTED", "RECOVERY_REQUIRED", "ESTABLISHED")
                .contains(aggregate.linkStatus())
                || (aggregate.workflowInstanceId() != null
                    && !aggregate.workflowInstanceId().equals(message.workflowInstanceId()))
                || message.businessOccurredAt().isBefore(aggregate.requestedAt())) {
            return "WORKFLOW_INSTANCE_CONFLICT";
        }
        if (message.binding().kind() == WorkflowReverseContract.Kind.INSTANCE_ESTABLISHED) {
            java.util.Set<String> allowed = allowPostState
                    ? java.util.Set.of("AWAITING_APPROVAL", "PENDING_ASSIGNMENT",
                    "APPROVAL_REJECTED", "ASSIGNED", "IN_PROGRESS", "RESOLVED", "CLOSED")
                    : java.util.Set.of("AWAITING_APPROVAL", "PENDING_ASSIGNMENT",
                    "APPROVAL_REJECTED");
            if (!allowed.contains(aggregate.ticketStatus())) {
                return "TICKET_STATE_CONFLICT";
            }
            return null;
        }
        java.util.Set<String> passedStates = allowPostState
                ? java.util.Set.of("PENDING_ASSIGNMENT", "ASSIGNED", "IN_PROGRESS",
                "RESOLVED", "CLOSED")
                : java.util.Set.of("PENDING_ASSIGNMENT");
        boolean targetAlreadyApplied = "PASSED".equals(message.outcome())
                ? passedStates.contains(aggregate.ticketStatus())
                : "APPROVAL_REJECTED".equals(aggregate.ticketStatus());
        if (!"AWAITING_APPROVAL".equals(aggregate.ticketStatus()) && !targetAlreadyApplied) {
            return "TICKET_APPROVAL_OUTCOME_CONFLICT";
        }
        if ("PASSED".equals(message.outcome())) {
            if (aggregate.slaPolicyId() == null || !"ACTIVE".equals(aggregate.policyStatus())
                    || aggregate.firstResponseSeconds() == null
                    || aggregate.resolutionSeconds() == null
                    || aggregate.firstResponseSeconds() <= 0 || aggregate.resolutionSeconds() <= 0
                    || invalidReminder(aggregate.firstResponseReminderSeconds(),
                        aggregate.firstResponseSeconds())
                    || invalidReminder(aggregate.resolutionReminderSeconds(),
                        aggregate.resolutionSeconds())) {
                return "TICKET_SLA_POLICY_INVALID";
            }
        }
        return null;
    }

    /**
     * 判断提醒提前量与时长关系是否无法形成合法提醒时间。
     */
    private static boolean invalidReminder(Long reminder, Long duration) {
        return reminder != null && (reminder < 0 || reminder > duration);
    }

    /**
     * 从消息稳定身份派生 Inbox 标识，使重复投递命中同一接收记录。
     */
    private static UUID inboxId(AcceptedWorkflowReverse message) {
        return IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(
                "FD-WORKFLOW-REVERSE-INBOX-V1", message.tenantId(), message.messageId()));
    }
}
