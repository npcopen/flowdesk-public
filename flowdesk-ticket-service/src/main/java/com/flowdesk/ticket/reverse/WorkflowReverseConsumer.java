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

/** Strict Ticket entry for instance confirmation and approval finalization. */
public final class WorkflowReverseConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowReverseConsumer.class);
    private final WorkflowReverseEnvelopeGuard guard;
    private final WorkflowReverseInboxRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final TicketNotificationOutboxWriter notificationWriter;
    private final SlaIndexProjection slaIndexProjection;

    WorkflowReverseConsumer(WorkflowReverseEnvelopeGuard guard,
                            WorkflowReverseInboxRepository repository,
                            PlatformTransactionManager transactionManager) {
        this(guard, repository, transactionManager, Clock.systemUTC(), null, null);
    }

    WorkflowReverseConsumer(WorkflowReverseEnvelopeGuard guard,
                            WorkflowReverseInboxRepository repository,
                            PlatformTransactionManager transactionManager,
                            Clock clock) {
        this(guard, repository, transactionManager, clock, null, null);
    }

    WorkflowReverseConsumer(WorkflowReverseEnvelopeGuard guard,
                            WorkflowReverseInboxRepository repository,
                            PlatformTransactionManager transactionManager,
                            Clock clock,
                            TicketNotificationOutboxWriter notificationWriter) {
        this(guard, repository, transactionManager, clock, notificationWriter, null);
    }

    WorkflowReverseConsumer(WorkflowReverseEnvelopeGuard guard,
                            WorkflowReverseInboxRepository repository,
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

    private WorkflowReverseConsumeResult process(AcceptedWorkflowReverse message) {
        WorkflowReverseInboxRepository.Duplicate duplicate = repository.findDuplicate(message, true);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (duplicate.kind() == WorkflowReverseInboxRepository.DuplicateKind.TERMINAL) {
            // A terminal Inbox is not proof that every deterministic business
            // anchor committed.  A crash can leave the Inbox row ahead of the
            // timeline, SLA, audit, or Notification Outbox rows.  Reconcile a
            // successfully processed source before acknowledging the duplicate;
            // rejected poison rows intentionally remain terminal and untouched.
            if (duplicate.inbox() != null
                    && "PROCESSED".equals(duplicate.inbox().status())) {
                reconcileProcessedDuplicate(message, now);
            }
            return WorkflowReverseConsumeResult.ack("WORKFLOW_REVERSE_DUPLICATE");
        }
        if (duplicate.kind() == WorkflowReverseInboxRepository.DuplicateKind.CONFLICT) {
            UUID target = duplicate.inbox().resultId() == null
                    ? message.ticketId() : duplicate.inbox().resultId();
            insertAuditOnce(message, target, "WORKFLOW_REVERSE_CONFLICT", "REJECTED",
                    "Message identity was reused with different content.",
                    duplicate.conflictCode(), now);
            return WorkflowReverseConsumeResult.ack(duplicate.conflictCode());
        }

        WorkflowReverseInboxRepository.Aggregate aggregate = repository.findAggregate(message);
        String invalid = validateAggregate(message, aggregate, duplicate.kind() !=
                WorkflowReverseInboxRepository.DuplicateKind.NONE);
        if (invalid != null) {
            if (duplicate.kind() == WorkflowReverseInboxRepository.DuplicateKind.RECOVERABLE) {
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
        WorkflowReverseInboxRepository.FinalizationResult finalization =
                WorkflowReverseInboxRepository.FinalizationResult.APPLIED;
        if (message.binding().kind() == WorkflowReverseContract.Kind.APPROVAL_FINALIZED) {
            finalization = repository.finalizeTicket(message, aggregate, now);
            // Both paths converge through deterministic effect identities.  A
            // recovery request may observe the terminal Ticket state after the
            // original transaction committed only part of its side effects, so
            // ALREADY_APPLIED must still verify and fill each missing anchor.
            if ("PASSED".equals(message.outcome())) {
                // SLA time starts when Ticket accepts the trusted terminal
                // fact, never at the remote Workflow event timestamp. On an
                // idempotent re-entry, reuse the persisted Ticket fact so a
                // late duplicate cannot move its deadline forward.
                Instant slaStartedAt = finalization
                        == WorkflowReverseInboxRepository.FinalizationResult.APPLIED
                        ? now : aggregate.firstProcessableAt();
                repository.insertSlaClocks(message, aggregate, slaStartedAt, now);
                scheduleSlaIndexProjections(message);
            }
            repository.insertTimeline(message, now);
            writeRequesterNotification(message, aggregate, now);
        }
        if (duplicate.kind() == WorkflowReverseInboxRepository.DuplicateKind.RECOVERABLE) {
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

    private void reconcileProcessedDuplicate(AcceptedWorkflowReverse message, Instant now) {
        WorkflowReverseInboxRepository.Aggregate aggregate = repository.findAggregate(message);
        String invalid = validateAggregate(message, aggregate, true);
        if (invalid != null) {
            // A processed source must never be silently accepted against a
            // different Ticket fact.  Returning a retry lets an operator repair
            // the missing/corrupt anchor without manufacturing a new Inbox row.
            throw new IllegalStateException("Processed Workflow reverse source cannot be reconciled: "
                    + invalid);
        }
        repository.establishLink(message, aggregate, now);
        if (message.binding().kind() == WorkflowReverseContract.Kind.APPROVAL_FINALIZED) {
            WorkflowReverseInboxRepository.FinalizationResult finalization =
                    repository.finalizeTicket(message, aggregate, now);
            if ("PASSED".equals(message.outcome())) {
                Instant startedAt = finalization
                        == WorkflowReverseInboxRepository.FinalizationResult.APPLIED
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

    private void insertAuditOnce(AcceptedWorkflowReverse message, UUID targetId,
                                 String action, String outcome, String reason,
                                 String errorCode, Instant now) {
        UUID auditId = WorkflowReverseInboxRepository.auditId(message, action, targetId,
                outcome, reason, errorCode);
        WorkflowReverseInboxRepository.Audit existing = repository.findAudit(message, auditId)
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
            WorkflowReverseInboxRepository.Audit raced = repository.findAudit(message, auditId)
                    .orElseThrow(() -> duplicate);
            if (!repository.sameAudit(raced, auditId, targetId, action, outcome,
                    reason, errorCode, message)) {
                throw new IllegalStateException("Workflow reverse audit identity/content conflict", duplicate);
            }
        }
    }

    private void writeRequesterNotification(AcceptedWorkflowReverse message,
                                             WorkflowReverseInboxRepository.Aggregate aggregate,
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
                WorkflowReverseInboxRepository.finalizationEffectKey(message), "TICKET",
                message.ticketId(), title, body,
                message.correlationId(), message.messageId(), now));
    }

    private void scheduleSlaIndexProjections(AcceptedWorkflowReverse message) {
        if (slaIndexProjection == null) {
            return;
        }
        slaIndexProjection.schedule(message.tenantId(),
                WorkflowReverseInboxRepository.slaClockId(message.tenantId(), message.ticketId(),
                        "FIRST_RESPONSE"));
        slaIndexProjection.schedule(message.tenantId(),
                WorkflowReverseInboxRepository.slaClockId(message.tenantId(), message.ticketId(),
                        "RESOLUTION"));
    }

    private static String validateAggregate(AcceptedWorkflowReverse message,
                                            WorkflowReverseInboxRepository.Aggregate aggregate,
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

    private static boolean invalidReminder(Long reminder, Long duration) {
        return reminder != null && (reminder < 0 || reminder > duration);
    }

    private static UUID inboxId(AcceptedWorkflowReverse message) {
        return IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(
                "FD-WORKFLOW-REVERSE-INBOX-V1", message.tenantId(), message.messageId()));
    }
}
