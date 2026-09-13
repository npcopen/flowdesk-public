package com.flowdesk.ticket.reverse;

import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.ticket.repository.TicketRepository.Uuids;
import com.flowdesk.ticket.service.IdempotencyCodec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** All reverse-consumption reads and writes are tenant-first and transaction-scoped. */
public final class WorkflowReverseInboxRepository {
    private final JdbcTemplate jdbc;

    public WorkflowReverseInboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Duplicate findDuplicate(AcceptedWorkflowReverse message, boolean lock) {
        requireTenant(message.tenantId());
        String suffix = lock ? " FOR UPDATE" : "";
        Optional<Inbox> byMessage = jdbc.query(inboxSelect()
                + " WHERE tenant_id=? AND producer_service=? AND message_id=?" + suffix,
                INBOX_MAPPER, Uuids.toBytes(message.tenantId()),
                WorkflowReverseContract.PRODUCER, Uuids.toBytes(message.messageId()))
                .stream().findFirst();
        if (byMessage.isPresent()) return classify(message, byMessage.get(), "MESSAGE_ID_CONTENT_CONFLICT");
        Optional<Inbox> byIntent = jdbc.query(inboxSelect()
                + " WHERE tenant_id=? AND producer_service=? AND contract_ref=?"
                + " AND business_intent_key=?" + suffix, INBOX_MAPPER,
                Uuids.toBytes(message.tenantId()),
                WorkflowReverseContract.PRODUCER, message.binding().contractRef(),
                message.businessIntentKey()).stream().findFirst();
        return byIntent.map(inbox -> classify(message, inbox, "BUSINESS_INTENT_CONTENT_CONFLICT"))
                .orElseGet(Duplicate::none);
    }

    private static Duplicate classify(AcceptedWorkflowReverse message, Inbox inbox, String conflict) {
        boolean same = message.binding().contractRef().equals(inbox.contractRef())
                && message.messageId().equals(inbox.messageId())
                && inbox.contractVersion() == WorkflowReverseContract.VERSION
                && Arrays.equals(message.businessIntentKey(), inbox.intent())
                && Arrays.equals(message.bodySha256(), inbox.bodyHash())
                && Arrays.equals(message.bodyBytes(), inbox.body())
                && Objects.equals(message.correlationId(), inbox.correlationId())
                && Objects.equals(message.causationId(), inbox.causationId())
                && Objects.equals(message.envelopeOccurredAt(), inbox.occurredAt());
        if (!same) return new Duplicate(DuplicateKind.CONFLICT, inbox, conflict);
        if ("RECOVERY_REQUIRED".equals(inbox.status())) {
            return new Duplicate(DuplicateKind.RECOVERABLE, inbox, null);
        }
        return new Duplicate(DuplicateKind.TERMINAL, inbox, null);
    }

    public Aggregate findAggregate(AcceptedWorkflowReverse message) {
        requireTenant(message.tenantId());
        return jdbc.query("""
                SELECT t.ticket_id,t.ticket_no,t.status AS ticket_status,t.workflow_definition_id,
                       t.workflow_version_id,t.workflow_version_no,t.catalog_item_id,
                       t.requester_user_id,t.assignee_user_id,t.first_processable_at,
                       t.lock_version AS ticket_lock_version,
                       l.start_intent_key,l.status AS link_status,l.workflow_instance_id,
                       l.requested_at,l.established_at,l.lock_version AS link_lock_version,
                       c.sla_policy_id,p.first_response_seconds,p.resolution_seconds,
                       p.first_response_reminder_before_seconds,
                       p.resolution_reminder_before_seconds,p.status AS policy_status
                FROM ticket t
                LEFT JOIN workflow_start_link l
                  ON l.tenant_id=t.tenant_id AND l.ticket_id=t.ticket_id
                LEFT JOIN service_catalog_item c
                  ON c.tenant_id=t.tenant_id AND c.catalog_item_id=t.catalog_item_id
                LEFT JOIN sla_policy p
                  ON p.tenant_id=c.tenant_id AND p.sla_policy_id=c.sla_policy_id
                WHERE t.tenant_id=? AND t.ticket_id=?
                FOR UPDATE
                """, AGGREGATE_MAPPER, Uuids.toBytes(message.tenantId()),
                Uuids.toBytes(message.ticketId())).stream().findFirst().orElse(null);
    }

    public void establishLink(AcceptedWorkflowReverse message, Aggregate aggregate, Instant now) {
        if (!"ESTABLISHED".equals(aggregate.linkStatus())) {
            int updated = jdbc.update("""
                UPDATE workflow_start_link
                SET status='ESTABLISHED',workflow_instance_id=?,established_at=?,
                    recovery_required_at=NULL,recovery_reason_code=NULL,
                    lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',
                    updated_by_id=NULL
                WHERE tenant_id=? AND ticket_id=? AND status IN ('REQUESTED','RECOVERY_REQUIRED')
                  AND lock_version=?
                """, Uuids.toBytes(message.workflowInstanceId()), Timestamp.from(now),
                Timestamp.from(now), Uuids.toBytes(message.tenantId()),
                Uuids.toBytes(message.ticketId()), aggregate.linkLockVersion());
            requireOne(updated, "WorkflowStartLink establish");
        }
        resolveStartPublicationRecovery(message, now);
    }

    private void resolveStartPublicationRecovery(AcceptedWorkflowReverse message, Instant now) {
        List<RecoveryState> recoveries = jdbc.query("""
                SELECT r.recovery_id,r.state,r.lock_version
                FROM message_recovery r
                JOIN outbox_event o ON o.tenant_id=r.tenant_id AND o.outbox_id=r.source_record_id
                WHERE r.tenant_id=? AND r.direction='OUTBOX'
                  AND o.aggregate_type='TICKET' AND o.aggregate_id=?
                  AND o.contract_ref='flowdesk.workflow.start.v1' AND o.contract_version=1
                FOR UPDATE
                """, RECOVERY_STATE_MAPPER, Uuids.toBytes(message.tenantId()),
                Uuids.toBytes(message.ticketId()));
        for (RecoveryState recovery : recoveries) {
            if ("RESOLVED".equals(recovery.state())) {
                continue;
            }
            if (!Set.of("OPEN", "REPLAYING").contains(recovery.state())) {
                throw new IllegalStateException("Workflow start recovery state conflict");
            }
            int changed = jdbc.update("""
                    UPDATE message_recovery
                    SET state='RESOLVED',resolved_at=?,next_attempt_at=NULL,
                        lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                    WHERE tenant_id=? AND recovery_id=? AND state=? AND lock_version=?
                    """, Timestamp.from(now), Timestamp.from(now), Uuids.toBytes(message.tenantId()),
                    Uuids.toBytes(recovery.recoveryId()), recovery.state(), recovery.lockVersion());
            requireOne(changed, "Workflow start recovery resolution");
        }
    }

    public FinalizationResult finalizeTicket(AcceptedWorkflowReverse message,
                                             Aggregate aggregate, Instant now) {
        String target = "PASSED".equals(message.outcome())
                ? "PENDING_ASSIGNMENT" : "APPROVAL_REJECTED";
        int updated;
        if ("PASSED".equals(message.outcome())) {
            updated = jdbc.update("""
                    UPDATE ticket
                    SET status='PENDING_ASSIGNMENT',first_processable_at=?,terminal_at=NULL,
                        lock_version=lock_version+1,
                        updated_at=CASE WHEN updated_at >= ?
                            THEN TIMESTAMPADD(MICROSECOND,1,updated_at) ELSE ? END,
                        updated_by_type='SERVICE',
                        updated_by_id=NULL
                    WHERE tenant_id=? AND ticket_id=? AND status='AWAITING_APPROVAL'
                      AND lock_version=?
                    """, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                    Uuids.toBytes(message.tenantId()), Uuids.toBytes(message.ticketId()),
                    aggregate.ticketLockVersion());
        }
        else {
            updated = jdbc.update("""
                    UPDATE ticket
                    SET status='APPROVAL_REJECTED',terminal_at=?,first_processable_at=NULL,
                        lock_version=lock_version+1,
                        updated_at=CASE WHEN updated_at >= ?
                            THEN TIMESTAMPADD(MICROSECOND,1,updated_at) ELSE ? END,
                        updated_by_type='SERVICE',
                        updated_by_id=NULL
                    WHERE tenant_id=? AND ticket_id=? AND status='AWAITING_APPROVAL'
                      AND lock_version=?
                    """, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                    Uuids.toBytes(message.tenantId()), Uuids.toBytes(message.ticketId()),
                    aggregate.ticketLockVersion());
        }
        if (updated == 1) return FinalizationResult.APPLIED;
        // Recovery may be requested after the business transaction already
        // committed.  A PASSED Ticket can legitimately have advanced through
        // assignment, work, resolution, or close before a duplicate/replay is
        // observed.  Those states are durable descendants of the same terminal
        // fact and must be reconciled without changing the Ticket again.
        if (("PASSED".equals(message.outcome()) && Set.of("PENDING_ASSIGNMENT",
                "ASSIGNED", "IN_PROGRESS", "RESOLVED", "CLOSED")
                .contains(aggregate.ticketStatus()))
                || ("REJECTED".equals(message.outcome()) && "APPROVAL_REJECTED".equals(aggregate.ticketStatus()))) {
            return FinalizationResult.ALREADY_APPLIED;
        }
        requireOne(updated, "Ticket approval finalization to " + target);
        return FinalizationResult.APPLIED;
    }

    public void insertSlaClocks(AcceptedWorkflowReverse message, Aggregate aggregate,
                                Instant startedAt, Instant recordedAt) {
        if (startedAt == null || recordedAt == null) {
            throw new IllegalArgumentException("Workflow reverse SLA start/recorded time is required");
        }
        Instant start = startedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant recorded = recordedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        insertClock(message, aggregate, "FIRST_RESPONSE", aggregate.firstResponseSeconds(),
                aggregate.firstResponseReminderSeconds(), start, recorded);
        insertClock(message, aggregate, "RESOLUTION", aggregate.resolutionSeconds(),
                aggregate.resolutionReminderSeconds(), start, recorded);
    }

    /**
     * Compatibility entry point for callers that already hold the persisted
     * Ticket start fact. Workflow's occurredAt is causal metadata only and is
     * deliberately never used to reconstruct an SLA deadline.
     */
    public void insertSlaClocks(AcceptedWorkflowReverse message, Aggregate aggregate,
                                Instant recordedAt) {
        Instant start = aggregate.firstProcessableAt();
        if (start == null) {
            throw new IllegalStateException("Ticket finalization has no persisted SLA start time");
        }
        insertSlaClocks(message, aggregate, start, recordedAt);
    }

    private void insertClock(AcceptedWorkflowReverse message, Aggregate aggregate,
                              String type, Long duration, Long reminder,
                              Instant started, Instant recordedAt) {
        if (started == null || recordedAt == null || duration == null || duration <= 0) {
            throw new IllegalArgumentException("Workflow reverse SLA clock timestamps are incomplete");
        }
        UUID clockId = slaClockId(message.tenantId(), message.ticketId(), type);
        Instant deadline = started.plusSeconds(duration);
        Instant reminderAt = reminder == null ? null : deadline.minusSeconds(reminder);
        ClockIdentity existing = jdbc.query("""
                SELECT sla_clock_id,sla_policy_id,status,started_at,deadline_at,reminder_due_at,
                       policy_duration_seconds,policy_reminder_before_seconds,updated_at
                FROM sla_clock
                WHERE tenant_id=? AND ticket_id=? AND clock_type=?
                LIMIT 1
                """, CLOCK_IDENTITY_MAPPER, Uuids.toBytes(message.tenantId()),
                Uuids.toBytes(message.ticketId()), type).stream().findFirst().orElse(null);
        if (existing != null) {
            if (!clockId.equals(existing.clockId())
                    || !aggregate.slaPolicyId().equals(existing.slaPolicyId())
                    || !started.equals(existing.startedAt())
                    || !deadline.equals(existing.deadlineAt())
                    || !Objects.equals(reminderAt, existing.reminderDueAt())
                    || duration.longValue() != existing.duration()
                    || !Objects.equals(reminder, existing.reminder())) {
                throw new IllegalStateException("Workflow reverse SLA clock identity/content conflict");
            }
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO sla_clock
                      (tenant_id,sla_clock_id,ticket_id,sla_policy_id,clock_type,status,
                       started_at,deadline_at,reminder_due_at,policy_duration_seconds,
                       policy_reminder_before_seconds,milestone_at,breached_at,satisfied_at,
                       lock_version,created_at,created_by_type,created_by_id,
                       updated_at,updated_by_type,updated_by_id)
                    VALUES (?,?,?,?,?,'RUNNING',?,?,?,?,?,NULL,NULL,NULL,0,
                            ?,'SERVICE',NULL,?,'SERVICE',NULL)
                    """, Uuids.toBytes(message.tenantId()), Uuids.toBytes(clockId),
                    Uuids.toBytes(message.ticketId()), Uuids.toBytes(aggregate.slaPolicyId()), type,
                     Timestamp.from(started), Timestamp.from(deadline), timestamp(reminderAt),
                     duration, reminder, Timestamp.from(recordedAt), Timestamp.from(recordedAt));
        }
        catch (DuplicateKeyException duplicate) {
            ClockIdentity raced = jdbc.query("""
                    SELECT sla_clock_id,sla_policy_id,status,started_at,deadline_at,reminder_due_at,
                           policy_duration_seconds,policy_reminder_before_seconds,updated_at
                    FROM sla_clock
                    WHERE tenant_id=? AND ticket_id=? AND clock_type=?
                    LIMIT 1
                    """, CLOCK_IDENTITY_MAPPER, Uuids.toBytes(message.tenantId()),
                    Uuids.toBytes(message.ticketId()), type).stream().findFirst()
                    .orElseThrow(() -> duplicate);
            if (!clockId.equals(raced.clockId()) || !aggregate.slaPolicyId().equals(raced.slaPolicyId())
                    || !started.equals(raced.startedAt())
                    || !deadline.equals(raced.deadlineAt()) || !Objects.equals(reminderAt, raced.reminderDueAt())
                    || duration.longValue() != raced.duration() || !Objects.equals(reminder, raced.reminder())) {
                throw new IllegalStateException("Workflow reverse SLA clock identity/content conflict", duplicate);
            }
        }
    }

    /** Stable identity shared by reverse recovery and post-commit SLA projection. */
    public static UUID slaClockId(UUID tenantId, UUID ticketId, String clockType) {
        if (tenantId == null || ticketId == null
                || !("FIRST_RESPONSE".equals(clockType) || "RESOLUTION".equals(clockType))) {
            throw new IllegalArgumentException("Workflow reverse SLA clock identity is invalid");
        }
        return IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(
                "FD-WORKFLOW-SLA-CLOCK-V1", tenantId, ticketId, clockType));
    }

    public void insertTimeline(AcceptedWorkflowReverse message) {
        insertTimeline(message, message.businessOccurredAt());
    }

    public void insertTimeline(AcceptedWorkflowReverse message, Instant recordedAt) {
        String event = "PASSED".equals(message.outcome()) ? "APPROVAL_PASSED" : "APPROVAL_REJECTED";
        String summary = "PASSED".equals(message.outcome()) ? "审批已通过。" : "审批已拒绝。";
        if (message.decisionReason() != null) summary = message.decisionReason();
        byte[] effectKey = finalizationEffectKey(message);
        UUID timelineId = IdempotencyCodec.uuidFromDigest(effectKey);
        Timeline existing = findTimeline(message, effectKey).orElse(null);
        if (existing != null) {
            if (!sameTimeline(existing, timelineId, message, event, summary, effectKey)) {
                throw new IllegalStateException("Workflow reverse timeline identity/content conflict");
            }
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO ticket_timeline_entry
                      (tenant_id,timeline_id,ticket_id,effect_key,event_type,summary,
                       related_ref_type,related_ref_id,actor_type,actor_id,occurred_at,
                       correlation_id,causation_id,created_at)
                     VALUES (?,?,?,?,?,?,'WORKFLOW_INSTANCE',?,'SERVICE',NULL,?,?,?,?)
                    """,
                    Uuids.toBytes(message.tenantId()), Uuids.toBytes(timelineId),
                    Uuids.toBytes(message.ticketId()), effectKey, event, summary,
                    Uuids.toBytes(message.workflowInstanceId()), Timestamp.from(message.businessOccurredAt()),
                    Uuids.toBytes(message.correlationId()), Uuids.toBytes(message.causationId()),
                    Timestamp.from(recordedAt));
        }
        catch (DuplicateKeyException duplicate) {
            Timeline raced = findTimeline(message, effectKey).orElseThrow(() -> duplicate);
            if (!sameTimeline(raced, timelineId, message, event, summary, effectKey)) {
                throw new IllegalStateException("Workflow reverse timeline identity/content conflict", duplicate);
            }
        }
    }

    private Optional<Timeline> findTimeline(AcceptedWorkflowReverse message, byte[] effectKey) {
        requireTenant(message.tenantId());
        return jdbc.query("""
                SELECT timeline_id,effect_key,event_type,summary,related_ref_type,related_ref_id,
                       actor_type,actor_id,occurred_at,correlation_id,causation_id
                FROM ticket_timeline_entry
                WHERE tenant_id=? AND ticket_id=? AND effect_key=?
                """, TIMELINE_MAPPER, Uuids.toBytes(message.tenantId()),
                Uuids.toBytes(message.ticketId()), effectKey).stream().findFirst();
    }

    private static boolean sameTimeline(Timeline row, UUID timelineId,
                                        AcceptedWorkflowReverse message, String event,
                                        String summary, byte[] effectKey) {
        return timelineId.equals(row.timelineId())
                && Objects.equals(row.eventType(), event)
                && Objects.equals(row.summary(), summary)
                && Objects.equals(row.relatedRefType(), "WORKFLOW_INSTANCE")
                && message.workflowInstanceId().equals(row.relatedRefId())
                && Objects.equals(row.actorType(), "SERVICE")
                && row.actorId() == null
                && Arrays.equals(row.effectKey(), effectKey)
                && Objects.equals(row.occurredAt(), message.businessOccurredAt())
                && Objects.equals(row.correlationId(), message.correlationId())
                && Objects.equals(row.causationId(), message.causationId());
    }

    /**
     * Stable terminal effect identity. Ordinary PASSED/REJECTED forms retain
     * the M6 key shape; conditionResult=false gets a distinct suffix so an
     * auto-pass cannot converge with a real approval.
     */
    public static byte[] finalizationEffectKey(AcceptedWorkflowReverse message) {
        if ("PASSED".equals(message.outcome()) && Boolean.FALSE.equals(message.conditionResult())) {
            return IdempotencyCodec.derivedKey("FD-WORKFLOW-APPROVAL-FINALIZED-V1",
                    message.tenantId(), message.ticketId(), message.workflowInstanceId(),
                    message.outcome(), "AUTO_PASS");
        }
        return IdempotencyCodec.derivedKey("FD-WORKFLOW-APPROVAL-FINALIZED-V1",
                message.tenantId(), message.ticketId(), message.workflowInstanceId(),
                message.outcome());
    }

    public void insertProcessedInbox(AcceptedWorkflowReverse message, UUID inboxId, Instant now) {
        insertInbox(message, inboxId, "PROCESSED", "TICKET", message.ticketId(), 1,
                null, now, now);
    }

    public void insertRejectedInbox(AcceptedWorkflowReverse message, UUID inboxId,
                                    String code, Instant now) {
        insertInbox(message, inboxId, "REJECTED", null, null, 1, code, now, now);
    }

    public void insertRecoveryInbox(AcceptedWorkflowReverse message, UUID inboxId,
                                    String code, Instant now) {
        insertInbox(message, inboxId, "RECOVERY_REQUIRED", null, null,
                WorkflowReverseContract.DELIVERY_ATTEMPTS, code, now, null);
    }

    private void insertInbox(AcceptedWorkflowReverse message, UUID inboxId, String status,
                             String resultType, UUID resultId, int attempts, String error,
                             Instant now, Instant processedAt) {
        requireTransaction();
        UUID stableInboxId = IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(
                "FD-WORKFLOW-REVERSE-INBOX-V1", message.tenantId(), message.messageId()));
        if (inboxId != null && !stableInboxId.equals(inboxId)) {
            throw new IllegalStateException("Workflow reverse Inbox identity is not deterministic");
        }
        try {
            jdbc.update("""
                    INSERT INTO inbox_message
                      (tenant_id,inbox_id,producer_service,message_id,business_intent_key,
                       contract_ref,contract_version,body_bytes,body_sha256,correlation_id,
                       causation_id,occurred_at,status,result_type,result_id,attempt_count,
                       last_error_code,first_received_at,last_attempt_at,processed_at,
                       lock_version,created_at,updated_at,updated_by_type,updated_by_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,'SERVICE',NULL)
                    """, Uuids.toBytes(message.tenantId()), Uuids.toBytes(stableInboxId),
                    WorkflowReverseContract.PRODUCER, Uuids.toBytes(message.messageId()),
                    message.businessIntentKey(), message.binding().contractRef(),
                    WorkflowReverseContract.VERSION, message.bodyBytes(), message.bodySha256(),
                    Uuids.toBytes(message.correlationId()), Uuids.toBytes(message.causationId()),
                    Timestamp.from(message.envelopeOccurredAt()), status, resultType,
                    Uuids.toBytes(resultId), attempts, error, Timestamp.from(now), Timestamp.from(now),
                    timestamp(processedAt), Timestamp.from(now), Timestamp.from(now));
        }
        catch (DuplicateKeyException duplicate) {
            Inbox existing = findInbox(message.tenantId(), stableInboxId, false)
                    .orElseThrow(() -> duplicate);
            if (!sameSource(message, existing)) {
                throw new IllegalStateException("Workflow reverse Inbox identity/content conflict", duplicate);
            }
        }
    }

    public boolean completeRecoveredInbox(AcceptedWorkflowReverse message, Inbox inbox, Instant now) {
        int updated = jdbc.update("""
                UPDATE inbox_message
                SET status='PROCESSED',result_type='TICKET',result_id=?,
                    attempt_count=attempt_count+1,last_error_code=NULL,processed_at=?,
                    last_attempt_at=?,lock_version=lock_version+1,updated_at=?,
                    updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND inbox_id=? AND status='RECOVERY_REQUIRED' AND lock_version=?
                """, Uuids.toBytes(message.ticketId()), Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now), Uuids.toBytes(message.tenantId()), Uuids.toBytes(inbox.inboxId()),
                inbox.lockVersion());
        if (updated == 1) {
            resolveRecovery(message.tenantId(), inbox.inboxId(), now);
            return true;
        }
        // A concurrent replay may already have committed the same terminal
        // fact. Only that exact immutable result is an idempotent success.
        Inbox current = findInbox(message.tenantId(), inbox.inboxId(), true)
                .orElseThrow(() -> new IllegalStateException("Recovered Ticket Inbox disappeared"));
        if ("PROCESSED".equals(current.status())
                && "TICKET".equals(current.resultType())
                && message.ticketId().equals(current.resultId())
                && sameSource(message, current)) {
            resolveRecovery(message.tenantId(), inbox.inboxId(), now);
            return false;
        }
        throw new IllegalStateException("Recovered Ticket Inbox completion CAS/content conflict");
    }

    public boolean rejectRecoveredInbox(AcceptedWorkflowReverse message, Inbox inbox,
                                        String code, Instant now) {
        int updated = jdbc.update("""
                UPDATE inbox_message
                SET status='REJECTED',attempt_count=attempt_count+1,last_error_code=?,
                    processed_at=?,last_attempt_at=?,lock_version=lock_version+1,
                    updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND inbox_id=? AND status='RECOVERY_REQUIRED' AND lock_version=?
                """, code, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                Uuids.toBytes(message.tenantId()), Uuids.toBytes(inbox.inboxId()), inbox.lockVersion());
        if (updated == 1) {
            resolveRecovery(message.tenantId(), inbox.inboxId(), now);
            return true;
        }
        Inbox current = findInbox(message.tenantId(), inbox.inboxId(), true)
                .orElseThrow(() -> new IllegalStateException("Recovered Ticket Inbox disappeared"));
        if ("REJECTED".equals(current.status()) && current.resultType() == null
                && current.resultId() == null && Objects.equals(code, current.lastErrorCode())
                && sameSource(message, current)) {
            resolveRecovery(message.tenantId(), inbox.inboxId(), now);
            return false;
        }
        throw new IllegalStateException("Recovered Ticket Inbox rejection CAS/content conflict");
    }

    public void insertRecovery(AcceptedWorkflowReverse message, UUID inboxId, UUID recoveryId,
                               String code, String summary, Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        UUID stableInboxId = IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(
                "FD-WORKFLOW-REVERSE-INBOX-V1", message.tenantId(), message.messageId()));
        UUID stableRecoveryId = IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(
                "FD-WORKFLOW-REVERSE-RECOVERY-V1", message.tenantId(), stableInboxId));
        if (inboxId != null && !stableInboxId.equals(inboxId)) {
            throw new IllegalStateException("Workflow reverse Recovery source identity is not deterministic");
        }
        if (recoveryId != null && !stableRecoveryId.equals(recoveryId)) {
            throw new IllegalStateException("Workflow reverse Recovery identity is not deterministic");
        }
        try {
            jdbc.update("""
                INSERT INTO message_recovery
                  (tenant_id,recovery_id,direction,source_record_id,business_intent_key,
                   state,attempt_count,next_attempt_at,last_error_code,last_error_summary,
                   dead_lettered_at,recovery_required_at,replay_count,resolved_at,
                   lock_version,created_at,updated_at,updated_by_type,updated_by_id)
                VALUES (?,?,'INBOX',?,?,'OPEN',?,NULL,?,?,NULL,?,0,NULL,0,?,?,'SERVICE',NULL)
                """, Uuids.toBytes(message.tenantId()), Uuids.toBytes(stableRecoveryId),
                    Uuids.toBytes(stableInboxId), message.businessIntentKey(),
                    WorkflowReverseContract.DELIVERY_ATTEMPTS, code, summary,
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        }
        catch (DuplicateKeyException duplicate) {
            RecoveryIdentity existing = jdbc.query("""
                    SELECT recovery_id,business_intent_key,state,attempt_count,last_error_code,
                           last_error_summary
                    FROM message_recovery
                    WHERE tenant_id=? AND direction='INBOX' AND source_record_id=?
                    """, RECOVERY_IDENTITY_MAPPER, Uuids.toBytes(message.tenantId()),
                    Uuids.toBytes(stableInboxId)).stream().findFirst().orElseThrow(() -> duplicate);
            if (!stableRecoveryId.equals(existing.recoveryId())
                    || !Arrays.equals(message.businessIntentKey(), existing.intent())
                    || !"OPEN".equals(existing.state())
                    || existing.attemptCount() != WorkflowReverseContract.DELIVERY_ATTEMPTS
                    || !Objects.equals(code, existing.errorCode())
                    || !Objects.equals(summary, existing.summary())) {
                throw new IllegalStateException("Conflicting Workflow reverse recovery identity", duplicate);
            }
        }
    }

    private void resolveRecovery(UUID tenantId, UUID inboxId, Instant now) {
        RecoveryState current = jdbc.query("""
                SELECT recovery_id,state,lock_version
                FROM message_recovery
                WHERE tenant_id=? AND direction='INBOX' AND source_record_id=?
                FOR UPDATE
                """, RECOVERY_STATE_MAPPER, Uuids.toBytes(tenantId),
                Uuids.toBytes(inboxId)).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Workflow reverse recovery row missing"));
        if ("RESOLVED".equals(current.state())) return;
        if (!Set.of("OPEN", "REPLAYING").contains(current.state())) {
            throw new IllegalStateException("Workflow reverse recovery state conflict: " + current.state());
        }
        int updated = jdbc.update("""
                UPDATE message_recovery
                SET state='RESOLVED',resolved_at=?,next_attempt_at=NULL,
                    lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND recovery_id=? AND state=? AND lock_version=?
                """, Timestamp.from(now), Timestamp.from(now), Uuids.toBytes(tenantId),
                Uuids.toBytes(current.recoveryId()), current.state(), current.lockVersion());
        requireOne(updated, "Workflow reverse recovery resolution");
    }

    private Optional<Inbox> findInbox(UUID tenantId, UUID inboxId, boolean lock) {
        requireTenant(tenantId);
        return jdbc.query(inboxSelect() + " WHERE tenant_id=? AND inbox_id=?"
                + (lock ? " FOR UPDATE" : ""), INBOX_MAPPER,
                Uuids.toBytes(tenantId), Uuids.toBytes(inboxId)).stream().findFirst();
    }

    public void insertAudit(AcceptedWorkflowReverse message, UUID targetId, String action,
                            String outcome, String reason, String error, Instant now) {
        insertAudit(message, auditId(message, action, targetId, outcome, reason, error),
                targetId, action, outcome, reason, error, now);
    }

    public void insertAudit(AcceptedWorkflowReverse message, UUID auditId, UUID targetId,
                            String action, String outcome, String reason, String error,
                            Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        try {
            jdbc.update("""
                INSERT INTO audit_record
                  (tenant_id,audit_id,action_code,target_type,target_id,outcome,
                   actor_type,actor_id,reason,error_code,correlation_id,causation_id,occurred_at)
                VALUES (?,?,?,'TICKET',?,?,'SERVICE',NULL,?,?,?,?,?)
                """, Uuids.toBytes(message.tenantId()), Uuids.toBytes(auditId), action,
                Uuids.toBytes(targetId), outcome, reason, error,
                Uuids.toBytes(message.correlationId()), Uuids.toBytes(message.causationId()),
                Timestamp.from(now));
        }
        catch (DuplicateKeyException duplicate) {
            Audit existing = findAudit(message, auditId).orElseThrow(() -> duplicate);
            if (!sameAudit(existing, auditId, targetId, action, outcome, reason, error, message)) {
                throw new IllegalStateException("Workflow reverse audit identity/content conflict", duplicate);
            }
        }
    }

    private static boolean sameSource(AcceptedWorkflowReverse message, Inbox row) {
        return message.messageId().equals(row.messageId())
                && message.binding().contractRef().equals(row.contractRef())
                && row.contractVersion() == WorkflowReverseContract.VERSION
                && Arrays.equals(message.businessIntentKey(), row.intent())
                && Arrays.equals(message.bodyBytes(), row.body())
                && Arrays.equals(message.bodySha256(), row.bodyHash())
                && Objects.equals(message.correlationId(), row.correlationId())
                && Objects.equals(message.causationId(), row.causationId())
                && Objects.equals(message.envelopeOccurredAt(), row.occurredAt());
    }

    private static String inboxSelect() {
        return """
                SELECT inbox_id,message_id,business_intent_key,contract_ref,contract_version,
                       body_bytes,body_sha256,correlation_id,causation_id,occurred_at,status,
                       result_type,result_id,attempt_count,last_error_code,lock_version
                FROM inbox_message
                """;
    }

    public Optional<Audit> findAudit(AcceptedWorkflowReverse message, UUID auditId) {
        requireTenant(message.tenantId());
        return jdbc.query("""
                SELECT audit_id,action_code,target_type,target_id,outcome,actor_type,actor_id,
                       reason,error_code,correlation_id,causation_id
                FROM audit_record WHERE tenant_id=? AND audit_id=?
                """, AUDIT_MAPPER, Uuids.toBytes(message.tenantId()), Uuids.toBytes(auditId))
                .stream().findFirst();
    }

    public static UUID auditId(AcceptedWorkflowReverse message, String action,
                               UUID targetId, String outcome, String reason, String error) {
        return IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(
                "FD-WORKFLOW-REVERSE-AUDIT-V1", message.tenantId(), message.messageId(),
                action, targetId == null ? new UUID(0L, 0L) : targetId, outcome));
    }

    public boolean sameAudit(Audit row, UUID auditId, UUID targetId, String action,
                             String outcome, String reason, String error,
                             AcceptedWorkflowReverse message) {
        return auditId.equals(row.auditId())
                && Objects.equals(row.actionCode(), action)
                && Objects.equals(row.targetType(), "TICKET")
                && Objects.equals(row.targetId(), targetId)
                && Objects.equals(row.outcome(), outcome)
                && Objects.equals(row.actorType(), "SERVICE")
                && row.actorId() == null
                && Objects.equals(row.reason(), reason)
                && Objects.equals(row.errorCode(), error)
                && Objects.equals(row.correlationId(), message.correlationId())
                && Objects.equals(row.causationId(), message.causationId());
    }

    private static void requireTenant(UUID tenantId) {
        TenantContextHolder.current().requireTenant(tenantId);
    }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Workflow reverse mutation requires a transaction");
        }
    }
    private static void requireOne(int updated, String operation) {
        if (updated != 1) throw new IllegalStateException(operation + " lost its single writer");
    }
    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column); return rs.wasNull() ? null : value;
    }
    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column); return rs.wasNull() ? null : value;
    }

    private static final org.springframework.jdbc.core.RowMapper<Inbox> INBOX_MAPPER =
            (rs, n) -> new Inbox(Uuids.toUuid(rs.getBytes("inbox_id")),
                    Uuids.toUuid(rs.getBytes("message_id")), rs.getBytes("business_intent_key"),
                    rs.getString("contract_ref"), rs.getInt("contract_version"),
                    rs.getBytes("body_bytes"), rs.getBytes("body_sha256"),
                    Uuids.toUuid(rs.getBytes("correlation_id")),
                    Uuids.toUuid(rs.getBytes("causation_id")), instant(rs, "occurred_at"),
                    rs.getString("status"),
                    rs.getString("result_type"), Uuids.toUuid(rs.getBytes("result_id")),
                    rs.getInt("attempt_count"), rs.getString("last_error_code"),
                    rs.getLong("lock_version"));
    private static final org.springframework.jdbc.core.RowMapper<Aggregate> AGGREGATE_MAPPER =
            (rs, n) -> new Aggregate(Uuids.toUuid(rs.getBytes("ticket_id")),
                    rs.getString("ticket_no"), rs.getString("ticket_status"),
                    Uuids.toUuid(rs.getBytes("workflow_definition_id")),
                    Uuids.toUuid(rs.getBytes("workflow_version_id")), nullableInt(rs, "workflow_version_no"),
                    Uuids.toUuid(rs.getBytes("requester_user_id")),
                    Uuids.toUuid(rs.getBytes("assignee_user_id")),
                    instant(rs, "first_processable_at"),
                    rs.getLong("ticket_lock_version"), rs.getBytes("start_intent_key"),
                    rs.getString("link_status"), Uuids.toUuid(rs.getBytes("workflow_instance_id")),
                    instant(rs, "requested_at"), instant(rs, "established_at"),
                    rs.getLong("link_lock_version"), Uuids.toUuid(rs.getBytes("sla_policy_id")),
                    nullableLong(rs, "first_response_seconds"), nullableLong(rs, "resolution_seconds"),
                    nullableLong(rs, "first_response_reminder_before_seconds"),
                    nullableLong(rs, "resolution_reminder_before_seconds"),
                     rs.getString("policy_status"));
    private static final org.springframework.jdbc.core.RowMapper<Timeline> TIMELINE_MAPPER =
            (rs, n) -> new Timeline(Uuids.toUuid(rs.getBytes("timeline_id")),
                    rs.getString("event_type"), rs.getString("summary"),
                    rs.getString("related_ref_type"), Uuids.toUuid(rs.getBytes("related_ref_id")),
                    rs.getString("actor_type"), Uuids.toUuid(rs.getBytes("actor_id")),
                    instant(rs, "occurred_at"), Uuids.toUuid(rs.getBytes("correlation_id")),
                    Uuids.toUuid(rs.getBytes("causation_id")), rs.getBytes("effect_key"));
    private static final org.springframework.jdbc.core.RowMapper<Audit> AUDIT_MAPPER =
            (rs, n) -> new Audit(Uuids.toUuid(rs.getBytes("audit_id")),
                    rs.getString("action_code"), rs.getString("target_type"),
                    Uuids.toUuid(rs.getBytes("target_id")), rs.getString("outcome"),
                    rs.getString("actor_type"), Uuids.toUuid(rs.getBytes("actor_id")),
                    rs.getString("reason"), rs.getString("error_code"),
                    Uuids.toUuid(rs.getBytes("correlation_id")),
                    Uuids.toUuid(rs.getBytes("causation_id")));
    private static final org.springframework.jdbc.core.RowMapper<ClockIdentity> CLOCK_IDENTITY_MAPPER =
            (rs, n) -> new ClockIdentity(Uuids.toUuid(rs.getBytes("sla_clock_id")),
                    Uuids.toUuid(rs.getBytes("sla_policy_id")), rs.getString("status"),
                    instant(rs, "started_at"), instant(rs, "deadline_at"),
                    instant(rs, "reminder_due_at"), rs.getLong("policy_duration_seconds"),
                    nullableLong(rs, "policy_reminder_before_seconds"));
    private static final org.springframework.jdbc.core.RowMapper<RecoveryIdentity>
            RECOVERY_IDENTITY_MAPPER = (rs, n) -> new RecoveryIdentity(
                    Uuids.toUuid(rs.getBytes("recovery_id")), rs.getBytes("business_intent_key"),
                    rs.getString("state"), rs.getInt("attempt_count"),
                    rs.getString("last_error_code"), rs.getString("last_error_summary"));
    private static final org.springframework.jdbc.core.RowMapper<RecoveryState> RECOVERY_STATE_MAPPER =
            (rs, n) -> new RecoveryState(Uuids.toUuid(rs.getBytes("recovery_id")),
                    rs.getString("state"), rs.getLong("lock_version"));

    public enum DuplicateKind { NONE, TERMINAL, RECOVERABLE, CONFLICT }
    public enum FinalizationResult { APPLIED, ALREADY_APPLIED }
    public record Duplicate(DuplicateKind kind, Inbox inbox, String conflictCode) {
        static Duplicate none() { return new Duplicate(DuplicateKind.NONE, null, null); }
    }
    public record Inbox(UUID inboxId, UUID messageId, byte[] intent, String contractRef,
                        int contractVersion, byte[] body, byte[] bodyHash, UUID correlationId,
                        UUID causationId, Instant occurredAt, String status, String resultType,
                        UUID resultId, int attemptCount, String lastErrorCode, long lockVersion) {
        public Inbox {
            intent = intent == null ? null : intent.clone();
            body = body == null ? null : body.clone();
            bodyHash = bodyHash == null ? null : bodyHash.clone();
        }
        @Override public byte[] intent() { return intent == null ? null : intent.clone(); }
        @Override public byte[] body() { return body == null ? null : body.clone(); }
        @Override public byte[] bodyHash() { return bodyHash == null ? null : bodyHash.clone(); }

        /** Compatibility constructor for older fixtures. */
        public Inbox(UUID inboxId, UUID messageId, byte[] intent, String contractRef,
                     int contractVersion, byte[] body, byte[] bodyHash, String status,
                     String resultType, UUID resultId, int attemptCount,
                     String lastErrorCode, long lockVersion) {
            this(inboxId, messageId, intent, contractRef, contractVersion, body, bodyHash,
                    null, null, null, status, resultType, resultId, attemptCount,
                    lastErrorCode, lockVersion);
        }
    }
    public record Timeline(UUID timelineId, String eventType, String summary,
                           String relatedRefType, UUID relatedRefId, String actorType,
                           UUID actorId, Instant occurredAt, UUID correlationId,
                           UUID causationId, byte[] effectKey) {
        public Timeline {
            effectKey = effectKey == null ? null : effectKey.clone();
        }
        @Override public byte[] effectKey() { return effectKey == null ? null : effectKey.clone(); }
    }
    public record Audit(UUID auditId, String actionCode, String targetType, UUID targetId,
                        String outcome, String actorType, UUID actorId, String reason,
                        String errorCode, UUID correlationId, UUID causationId) { }
    private record ClockIdentity(UUID clockId, UUID slaPolicyId, String status,
                                 Instant startedAt, Instant deadlineAt, Instant reminderDueAt,
                                 long duration, Long reminder) { }
    private record RecoveryIdentity(UUID recoveryId, byte[] intent, String state,
                                    int attemptCount, String errorCode, String summary) { }
    private record RecoveryState(UUID recoveryId, String state, long lockVersion) { }
    public record Aggregate(UUID ticketId, String ticketNo, String ticketStatus,
                            UUID definitionId, UUID versionId, Integer versionNo,
                            UUID requesterUserId, UUID assigneeUserId, Instant firstProcessableAt,
                            long ticketLockVersion,
                            byte[] startIntentKey,
                            String linkStatus, UUID workflowInstanceId, Instant requestedAt,
                            Instant establishedAt, long linkLockVersion, UUID slaPolicyId,
                            Long firstResponseSeconds, Long resolutionSeconds,
                            Long firstResponseReminderSeconds, Long resolutionReminderSeconds,
                            String policyStatus) { }
}
