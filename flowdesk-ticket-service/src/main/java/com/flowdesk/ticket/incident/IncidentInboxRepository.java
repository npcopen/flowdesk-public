package com.flowdesk.ticket.incident;

import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.ticket.repository.TicketRepository.Uuids;
import com.flowdesk.ticket.service.IdempotencyCodec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Narrow Ticket persistence entry for Alert -> Ticket Incident messages.
 * Every generated identity is derived from immutable tenant/message/effect
 * facts so a redelivery cannot create a second business fact.
 */
public final class IncidentInboxRepository {
    private static final String PRODUCER = IncidentMessageContract.ALERT_PRODUCER;
    private final JdbcTemplate jdbc;

    public IncidentInboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Duplicate duplicate(AcceptedIncidentMessage message, boolean lock) {
        requireTenant(message.tenantId());
        String suffix = lock ? " FOR UPDATE" : "";
        Optional<Inbox> byMessage = jdbc.query(inboxSelect()
                        + " WHERE tenant_id=? AND producer_service=? AND message_id=?" + suffix,
                INBOX_MAPPER, bytes(message.tenantId()), PRODUCER, bytes(message.messageId()))
                .stream().findFirst();
        if (byMessage.isPresent()) {
            return classify(message, byMessage.get(), "MESSAGE_ID_CONTENT_CONFLICT");
        }
        Optional<Inbox> byIntent = jdbc.query(inboxSelect()
                        + " WHERE tenant_id=? AND producer_service=? AND contract_ref=?"
                        + " AND business_intent_key=?" + suffix, INBOX_MAPPER,
                bytes(message.tenantId()), PRODUCER, message.binding().contractRef(),
                message.businessIntentKey()).stream().findFirst();
        return byIntent.map(row -> classify(message, row,
                        "BUSINESS_INTENT_CONTENT_CONFLICT"))
                .orElseGet(Duplicate::none);
    }

    private static Duplicate classify(AcceptedIncidentMessage message, Inbox row,
                                      String conflictCode) {
        boolean same = message.messageId().equals(row.messageId())
                && message.binding().contractRef().equals(row.contractRef())
                && row.contractVersion() == IncidentMessageContract.VERSION
                && Arrays.equals(message.businessIntentKey(), row.intent())
                && Arrays.equals(message.bodySha256(), row.bodyHash())
                && Arrays.equals(message.bodyBytes(), row.body())
                && Objects.equals(message.correlationId(), row.correlationId())
                && Objects.equals(message.causationId(), row.causationId())
                && Objects.equals(message.envelopeOccurredAt(), row.occurredAt());
        if (!same) return new Duplicate(DuplicateKind.CONFLICT, row, conflictCode);
        return new Duplicate("RECOVERY_REQUIRED".equals(row.status())
                ? DuplicateKind.RECOVERABLE : DuplicateKind.TERMINAL, row, null);
    }

    public Policy incidentPolicy(UUID tenantId) {
        requireTenant(tenantId);
        requireTransaction();
        List<Policy> rows = jdbc.query("""
                SELECT sla_policy_id,first_response_seconds,resolution_seconds,
                       first_response_reminder_before_seconds,
                       resolution_reminder_before_seconds
                FROM sla_policy
                WHERE tenant_id=? AND status='ACTIVE' AND is_incident_default=1
                FOR UPDATE
                """, POLICY_MAPPER, bytes(tenantId));
        return rows.size() == 1 ? rows.get(0) : null;
    }

    public Mapping mapping(UUID tenantId, UUID incidentId) {
        requireTenant(tenantId);
        return jdbc.query("""
                SELECT m.incident_id,m.ticket_id,m.creation_intent_key,m.established_at,
                       t.origin_type,t.status,t.creation_intent_key AS ticket_intent
                FROM incident_ticket_mapping m
                JOIN ticket t ON t.tenant_id=m.tenant_id AND t.ticket_id=m.ticket_id
                WHERE m.tenant_id=? AND m.incident_id=?
                """, MAPPING_MAPPER, bytes(tenantId), bytes(incidentId))
                .stream().findFirst().orElse(null);
    }

    public long nextSequence(UUID tenantId, LocalDate businessDate, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        jdbc.update("""
                INSERT INTO ticket_number_counter(tenant_id,business_date,sequence_value)
                VALUES(?,?,1)
                ON DUPLICATE KEY UPDATE sequence_value=sequence_value+1,updated_at=?
                """, bytes(tenantId), Date.valueOf(businessDate), Timestamp.from(now));
        Long value = jdbc.queryForObject("""
                SELECT sequence_value FROM ticket_number_counter
                WHERE tenant_id=? AND business_date=? FOR UPDATE
                """, Long.class, bytes(tenantId), Date.valueOf(businessDate));
        if (value == null || value < 1) throw new IllegalStateException("Ticket counter invalid");
        return value;
    }

    public void insertTicket(AcceptedIncidentMessage message, UUID ignoredTicketId,
                             String ticketNo, Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        UUID ticketId = ticketId(message);
        String subject = "Monitoring incident " + message.incidentId();
        String description = "Monitoring fingerprint: " + message.fingerprint()
                + (message.summary() == null ? "" : "\nSummary: " + message.summary());
        try {
            jdbc.update("""
                    INSERT INTO ticket(tenant_id,ticket_id,ticket_no,creation_intent_key,origin_type,
                     catalog_item_id,requester_user_id,previous_attempt_ticket_id,subject,description,status,
                     assignee_user_id,workflow_definition_id,workflow_version_id,workflow_version_no,
                     first_processable_at,resolved_at,closed_at,last_reopened_at,reopen_count,terminal_at,
                     lock_version,created_at,created_by_type,created_by_id,updated_at,updated_by_type,updated_by_id)
                    VALUES(?,?,?,?,'INCIDENT',NULL,NULL,NULL,?,?,'PENDING_ASSIGNMENT',NULL,NULL,NULL,NULL,
                           ?,NULL,NULL,NULL,0,NULL,0,?,'SERVICE',NULL,?,'SERVICE',NULL)
                    """, bytes(message.tenantId()), bytes(ticketId), ticketNo,
                    message.createTicketIntentKey(), subject, description,
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        }
        catch (DuplicateKeyException duplicate) {
            TicketIdentity existing = jdbc.query("""
                    SELECT ticket_id,creation_intent_key,origin_type,ticket_no,subject,description,status
                    FROM ticket WHERE tenant_id=? AND (ticket_id=? OR creation_intent_key=?)
                    """, TICKET_IDENTITY_MAPPER, bytes(message.tenantId()), bytes(ticketId),
                    message.createTicketIntentKey()).stream().findFirst().orElseThrow(() -> duplicate);
            if (!ticketId.equals(existing.ticketId())
                    || !Arrays.equals(message.createTicketIntentKey(), existing.intent())
                    || !"INCIDENT".equals(existing.originType())
                    || !Objects.equals(ticketNo, existing.ticketNo())
                    || !Objects.equals(subject, existing.subject())
                    || !Objects.equals(description, existing.description())) {
                throw new IllegalStateException("Incident Ticket identity/content conflict", duplicate);
            }
        }
    }

    public void insertMapping(AcceptedIncidentMessage message, UUID ignoredTicketId,
                              Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        UUID ticketId = ticketId(message);
        try {
            jdbc.update("""
                    INSERT INTO incident_ticket_mapping(tenant_id,incident_id,ticket_id,
                     creation_intent_key,established_at,created_at,created_by_type,created_by_id)
                    VALUES(?,?,?,?,?,?,'SERVICE',NULL)
                    """, bytes(message.tenantId()), bytes(message.incidentId()), bytes(ticketId),
                    message.createTicketIntentKey(), Timestamp.from(now), Timestamp.from(now));
        }
        catch (DuplicateKeyException duplicate) {
            Mapping existing = mapping(message.tenantId(), message.incidentId());
            if (existing == null || !ticketId.equals(existing.ticketId())
                    || !Arrays.equals(message.createTicketIntentKey(), existing.creationIntent())) {
                throw new IllegalStateException("Incident mapping identity/content conflict", duplicate);
            }
        }
    }

    public void insertTimeline(AcceptedIncidentMessage message, UUID ticketId,
                               byte[] effectKey, String eventType, String summary,
                               Instant occurredAt) {
        requireTenant(message.tenantId());
        requireTransaction();
        byte[] effect = effectKey.clone();
        UUID timelineId = stableUuid("FD-INCIDENT-TIMELINE-V1", message.tenantId(),
                ticketId, effect);
        try {
            jdbc.update("""
                    INSERT INTO ticket_timeline_entry(tenant_id,timeline_id,ticket_id,effect_key,
                      event_type,summary,related_ref_type,related_ref_id,actor_type,actor_id,
                      occurred_at,correlation_id,causation_id,created_at)
                    VALUES(?,?,?,?,?,?,'INCIDENT',?,'SERVICE',NULL,?,?,?,?)
                    """, bytes(message.tenantId()), bytes(timelineId), bytes(ticketId), effect,
                    eventType, summary, bytes(message.incidentId()), Timestamp.from(occurredAt),
                    bytes(message.correlationId()), bytes(message.messageId()), Timestamp.from(occurredAt));
        }
        catch (DuplicateKeyException duplicate) {
            TimelineIdentity existing = jdbc.query("""
                    SELECT timeline_id,event_type,summary,related_ref_type,related_ref_id,
                           actor_type,actor_id,occurred_at,correlation_id,causation_id,effect_key
                    FROM ticket_timeline_entry
                    WHERE tenant_id=? AND ticket_id=? AND effect_key=?
                    """, TIMELINE_IDENTITY_MAPPER, bytes(message.tenantId()), bytes(ticketId), effect)
                    .stream().findFirst().orElseThrow(() -> duplicate);
            if (!timelineId.equals(existing.timelineId())
                    || !Objects.equals(eventType, existing.eventType())
                    || !Objects.equals(summary, existing.summary())
                    || !"INCIDENT".equals(existing.relatedRefType())
                    || !message.incidentId().equals(existing.relatedRefId())
                    || !"SERVICE".equals(existing.actorType()) || existing.actorId() != null
                    || !Objects.equals(occurredAt, existing.occurredAt())
                    || !message.correlationId().equals(existing.correlationId())
                    || !message.messageId().equals(existing.causationId())
                    || !Arrays.equals(effect, existing.effectKey())) {
                throw new IllegalStateException("Incident timeline identity/content conflict", duplicate);
            }
        }
    }

    public UUID insertClock(AcceptedIncidentMessage message, UUID ticketId, Policy policy,
                            String clockType, long duration, Long reminder, Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        Instant deadline = now.plusSeconds(duration);
        Instant reminderDue = reminder == null ? null : deadline.minusSeconds(reminder);
        UUID clockId = slaClockId(message.tenantId(), ticketId, clockType);
        try {
            jdbc.update("""
                    INSERT INTO sla_clock(tenant_id,sla_clock_id,ticket_id,sla_policy_id,clock_type,status,
                     started_at,deadline_at,reminder_due_at,policy_duration_seconds,
                     policy_reminder_before_seconds,milestone_at,breached_at,satisfied_at,
                     lock_version,created_at,created_by_type,created_by_id,updated_at,updated_by_type,updated_by_id)
                    VALUES(?,?,?,?,?,'RUNNING',?,?,?,?,?,NULL,NULL,NULL,0,?,'SERVICE',NULL,?,'SERVICE',NULL)
                    """, bytes(message.tenantId()), bytes(clockId), bytes(ticketId), bytes(policy.policyId()),
                    clockType, Timestamp.from(now), Timestamp.from(deadline), timestamp(reminderDue),
                    duration, reminder, Timestamp.from(now), Timestamp.from(now));
        }
        catch (DuplicateKeyException duplicate) {
            ClockIdentity existing = jdbc.query("""
                    SELECT sla_clock_id,sla_policy_id,status,started_at,deadline_at,reminder_due_at,
                           policy_duration_seconds,policy_reminder_before_seconds
                    FROM sla_clock WHERE tenant_id=? AND ticket_id=? AND clock_type=?
                    """, CLOCK_IDENTITY_MAPPER, bytes(message.tenantId()), bytes(ticketId), clockType)
                    .stream().findFirst().orElseThrow(() -> duplicate);
            if (!clockId.equals(existing.clockId()) || !policy.policyId().equals(existing.policyId())
                    || !"RUNNING".equals(existing.status()) || !now.equals(existing.startedAt())
                    || !deadline.equals(existing.deadlineAt())
                    || !Objects.equals(reminderDue, existing.reminderDueAt())
                    || duration != existing.duration()
                    || !Objects.equals(reminder, existing.reminder())) {
                throw new IllegalStateException("Incident SLA clock identity/content conflict", duplicate);
            }
        }
        return clockId;
    }

    /** Stable clock identity shared by creation recovery and post-commit projection. */
    public static UUID slaClockId(UUID tenantId, UUID ticketId, String clockType) {
        if (tenantId == null || ticketId == null
                || !("FIRST_RESPONSE".equals(clockType) || "RESOLUTION".equals(clockType))) {
            throw new IllegalArgumentException("Incident SLA clock identity is invalid");
        }
        return stableUuid("FD-INCIDENT-SLA-CLOCK-V1", tenantId, ticketId, clockType);
    }

    public void insertOutboxEstablished(AcceptedIncidentMessage message, UUID ticketId,
                                        UUID ignoredOutboxId, UUID ignoredMessageId,
                                        byte[] ignoredPublicationKey, byte[] ignoredIntent,
                                        byte[] body, byte[] hash, Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        byte[] intent = IdempotencyCodec.derivedKey(
                IncidentMessageContract.ESTABLISHED_INTENT_NAMESPACE,
                message.tenantId(), message.incidentId());
        UUID messageId = stableUuid("FD-INCIDENT-ESTABLISHED-MESSAGE-V1",
                message.tenantId(), message.incidentId(), intent);
        byte[] publication = IdempotencyCodec.publicationKey(
                "FD-INCIDENT-ESTABLISHED-PUBLICATION-V1", message.tenantId(), messageId);
        UUID outboxId = stableUuid("FD-INCIDENT-ESTABLISHED-OUTBOX-V1",
                message.tenantId(), message.incidentId(), intent);
        try {
            jdbc.update("""
                    INSERT INTO outbox_event(tenant_id,outbox_id,message_id,publication_key,business_intent_key,
                     aggregate_type,aggregate_id,contract_ref,contract_version,body_bytes,body_sha256,
                     correlation_id,causation_id,status,attempt_count,next_attempt_at,locked_by,locked_at,
                     last_error_code,broker_confirmed_at,lock_version,created_at,created_by_type,created_by_id,
                     updated_at,updated_by_type,updated_by_id)
                    VALUES(?,?,?,?,?,'INCIDENT',?,?,1,?,?,?,?,'PENDING',0,?,NULL,NULL,NULL,NULL,0,?,'SERVICE',NULL,?,'SERVICE',NULL)
                    """, bytes(message.tenantId()), bytes(outboxId), bytes(messageId), publication, intent,
                    bytes(message.incidentId()), IncidentMessageContract.ESTABLISHED_CONTRACT_REF,
                    body, hash, bytes(message.correlationId()), bytes(message.messageId()),
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        }
        catch (DuplicateKeyException duplicate) {
            OutboxIdentity existing = jdbc.query("""
                    SELECT outbox_id,message_id,publication_key,business_intent_key,aggregate_id,
                           contract_ref,contract_version,body_bytes,body_sha256,correlation_id,causation_id
                    FROM outbox_event WHERE tenant_id=? AND publication_key=?
                    """, OUTBOX_IDENTITY_MAPPER, bytes(message.tenantId()), publication)
                    .stream().findFirst().orElseThrow(() -> duplicate);
            if (!outboxId.equals(existing.outboxId()) || !messageId.equals(existing.messageId())
                    || !Arrays.equals(publication, existing.publicationKey())
                    || !Arrays.equals(intent, existing.intent())
                    || !message.incidentId().equals(existing.aggregateId())
                    || !IncidentMessageContract.ESTABLISHED_CONTRACT_REF.equals(existing.contractRef())
                    || existing.contractVersion() != IncidentMessageContract.VERSION
                    || !Arrays.equals(body, existing.body()) || !Arrays.equals(hash, existing.hash())
                    || !message.correlationId().equals(existing.correlationId())
                    || !message.messageId().equals(existing.causationId())) {
                throw new IllegalStateException("Incident established Outbox identity/content conflict", duplicate);
            }
        }
    }

    public void insertAudit(AcceptedIncidentMessage message, UUID targetId, String action,
                            String outcome, String reason, String error, Instant now) {
        insertAudit(message, stableUuid("FD-INCIDENT-AUDIT-V1", message.tenantId(),
                        message.messageId(), targetId == null ? ZERO : targetId,
                        action, outcome, reason == null ? "" : reason,
                        error == null ? "" : error),
                targetId, action, outcome, reason, error, now);
    }

    public void insertAudit(AcceptedIncidentMessage message, UUID auditId, UUID targetId,
                            String action, String outcome, String reason, String error,
                            Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        try {
            jdbc.update("""
                    INSERT INTO audit_record(tenant_id,audit_id,action_code,target_type,target_id,outcome,
                     actor_type,actor_id,reason,error_code,correlation_id,causation_id,occurred_at,recorded_at)
                    VALUES(?,?,?,'TICKET',?,?,'SERVICE',NULL,?,?,?,?,?,?)
                    """, bytes(message.tenantId()), bytes(auditId), action, bytes(targetId), outcome,
                    reason, error, bytes(message.correlationId()), bytes(message.messageId()),
                    Timestamp.from(now), Timestamp.from(now));
        }
        catch (DuplicateKeyException duplicate) {
            AuditIdentity existing = jdbc.query("""
                    SELECT action_code,target_type,target_id,outcome,actor_type,actor_id,reason,error_code,
                           correlation_id,causation_id
                    FROM audit_record WHERE tenant_id=? AND audit_id=?
                    """, AUDIT_IDENTITY_MAPPER, bytes(message.tenantId()), bytes(auditId))
                    .stream().findFirst().orElseThrow(() -> duplicate);
            if (!Objects.equals(action, existing.action()) || !"TICKET".equals(existing.targetType())
                    || !Objects.equals(targetId, existing.targetId()) || !Objects.equals(outcome, existing.outcome())
                    || !"SERVICE".equals(existing.actorType()) || existing.actorId() != null
                    || !Objects.equals(reason, existing.reason()) || !Objects.equals(error, existing.error())
                    || !message.correlationId().equals(existing.correlationId())
                    || !message.messageId().equals(existing.causationId())) {
                throw new IllegalStateException("Incident audit identity/content conflict", duplicate);
            }
        }
    }

    public void insertProcessedInbox(AcceptedIncidentMessage message, UUID ignoredInbox,
                                     UUID result, Instant now) {
        insertInbox(message, "PROCESSED", "TICKET", result, 1, null, now, now);
    }

    public void insertRejectedInbox(AcceptedIncidentMessage message, UUID ignoredInbox,
                                    String code, Instant now) {
        insertInbox(message, "REJECTED", null, null, 1, code, now, now);
    }

    public void insertRecoveryInbox(AcceptedIncidentMessage message, UUID ignoredInbox,
                                    String code, Instant now) {
        insertInbox(message, "RECOVERY_REQUIRED", null, null,
                IncidentMessageContract.DELIVERY_ATTEMPTS, code, now, null);
    }

    private void insertInbox(AcceptedIncidentMessage message, String status, String resultType,
                             UUID result, int attempts, String error, Instant now,
                             Instant processedAt) {
        requireTenant(message.tenantId());
        requireTransaction();
        UUID inboxId = inboxId(message);
        try {
            jdbc.update("""
                    INSERT INTO inbox_message(tenant_id,inbox_id,producer_service,message_id,
                     business_intent_key,contract_ref,contract_version,body_sha256,body_bytes,
                     correlation_id,causation_id,occurred_at,status,result_type,result_id,attempt_count,
                     last_error_code,first_received_at,last_attempt_at,processed_at,lock_version,
                     created_at,updated_at,updated_by_type,updated_by_id)
                    VALUES(?,?,?,?,?,?,1,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,'SERVICE',NULL)
                    """, bytes(message.tenantId()), bytes(inboxId), PRODUCER,
                    bytes(message.messageId()), message.businessIntentKey(), message.binding().contractRef(),
                    message.bodySha256(), message.bodyBytes(), bytes(message.correlationId()),
                    bytes(message.causationId()), Timestamp.from(message.envelopeOccurredAt()), status,
                    resultType, bytes(result), attempts, error, Timestamp.from(now), Timestamp.from(now),
                    timestamp(processedAt), Timestamp.from(now), Timestamp.from(now));
        }
        catch (DuplicateKeyException duplicate) {
            Inbox existing = jdbc.query(inboxSelect() + " WHERE tenant_id=? AND inbox_id=?",
                    INBOX_MAPPER, bytes(message.tenantId()), bytes(inboxId))
                    .stream().findFirst().orElseThrow(() -> duplicate);
            if (!sameSource(message, existing)) {
                throw new IllegalStateException("Incident Inbox identity/content conflict", duplicate);
            }
        }
    }

    public void completeRecoveredInbox(AcceptedIncidentMessage message, Inbox inbox,
                                       UUID result, Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        int changed = jdbc.update("""
                UPDATE inbox_message SET status='PROCESSED',result_type='TICKET',result_id=?,
                  attempt_count=attempt_count+1,last_error_code=NULL,last_attempt_at=?,processed_at=?,
                  lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND inbox_id=? AND status='RECOVERY_REQUIRED' AND lock_version=?
                """, bytes(result), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                bytes(message.tenantId()), bytes(inbox.inboxId()), inbox.lockVersion());
        if (changed == 1) {
            resolveRecovery(message.tenantId(), inbox.inboxId(), now);
            return;
        }
        Inbox current = findInbox(message.tenantId(), inbox.inboxId(), true)
                .orElseThrow(() -> new IllegalStateException("Incident Inbox disappeared"));
        if (!"PROCESSED".equals(current.status()) || !"TICKET".equals(current.resultType())
                || !Objects.equals(result, current.resultId()) || !sameSource(message, current)) {
            throw new IllegalStateException("Incident Inbox completion CAS/content conflict");
        }
        resolveRecovery(message.tenantId(), inbox.inboxId(), now);
    }

    public void rejectRecoveredInbox(AcceptedIncidentMessage message, Inbox inbox,
                                     String code, Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        int changed = jdbc.update("""
                UPDATE inbox_message SET status='REJECTED',attempt_count=attempt_count+1,
                  last_error_code=?,last_attempt_at=?,processed_at=?,lock_version=lock_version+1,
                  updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND inbox_id=? AND status='RECOVERY_REQUIRED' AND lock_version=?
                """, code, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                bytes(message.tenantId()), bytes(inbox.inboxId()), inbox.lockVersion());
        if (changed == 1) {
            resolveRecovery(message.tenantId(), inbox.inboxId(), now);
            return;
        }
        Inbox current = findInbox(message.tenantId(), inbox.inboxId(), true)
                .orElseThrow(() -> new IllegalStateException("Incident Inbox disappeared"));
        if (!"REJECTED".equals(current.status()) || current.resultType() != null
                || current.resultId() != null || !Objects.equals(code, current.lastErrorCode())
                || !sameSource(message, current)) {
            throw new IllegalStateException("Incident Inbox rejection CAS/content conflict");
        }
        resolveRecovery(message.tenantId(), inbox.inboxId(), now);
    }

    public void insertRecovery(AcceptedIncidentMessage message, UUID ignoredInbox,
                               UUID ignoredRecovery, String code, Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        UUID inboxId = inboxId(message);
        UUID recoveryId = stableUuid("FD-INCIDENT-RECOVERY-V1", message.tenantId(), inboxId);
        String summary = "Incident message exhausted bounded Ticket retries.";
        try {
            jdbc.update("""
                    INSERT INTO message_recovery(tenant_id,recovery_id,direction,source_record_id,
                     business_intent_key,state,attempt_count,next_attempt_at,last_error_code,
                     last_error_summary,dead_lettered_at,recovery_required_at,replay_count,
                     last_replay_correlation_id,resolved_at,lock_version,created_at,updated_at,
                     updated_by_type,updated_by_id)
                    VALUES(?,?, 'INBOX',?,?,'OPEN',?,NULL,?, ?,NULL,?,0,NULL,NULL,0,?,?,'SERVICE',NULL)
                    """, bytes(message.tenantId()), bytes(recoveryId), bytes(inboxId),
                    message.businessIntentKey(), IncidentMessageContract.DELIVERY_ATTEMPTS, code,
                    summary, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        }
        catch (DuplicateKeyException duplicate) {
            RecoveryIdentity existing = jdbc.query("""
                    SELECT recovery_id,business_intent_key,state,attempt_count,last_error_code,last_error_summary
                    FROM message_recovery WHERE tenant_id=? AND direction='INBOX' AND source_record_id=?
                    """, RECOVERY_IDENTITY_MAPPER, bytes(message.tenantId()), bytes(inboxId))
                    .stream().findFirst().orElseThrow(() -> duplicate);
            if (!recoveryId.equals(existing.recoveryId())
                    || !Arrays.equals(message.businessIntentKey(), existing.intent())
                    || !"OPEN".equals(existing.state())
                    || existing.attemptCount() != IncidentMessageContract.DELIVERY_ATTEMPTS
                    || !Objects.equals(code, existing.errorCode())
                    || !Objects.equals(summary, existing.summary())) {
                throw new IllegalStateException("Incident Recovery identity/content conflict", duplicate);
            }
        }
    }

    private void resolveRecovery(UUID tenantId, UUID inboxId, Instant now) {
        requireTransaction();
        Optional<RecoveryState> state = jdbc.query("""
                SELECT recovery_id,state,lock_version FROM message_recovery
                WHERE tenant_id=? AND direction='INBOX' AND source_record_id=? FOR UPDATE
                """, RECOVERY_STATE_MAPPER, bytes(tenantId), bytes(inboxId)).stream().findFirst();
        if (state.isEmpty() || "RESOLVED".equals(state.get().state())) return;
        if (!SetOf.OPEN_REPLAYING.contains(state.get().state())) {
            throw new IllegalStateException("Incident Recovery state conflict");
        }
        int changed = jdbc.update("""
                UPDATE message_recovery SET state='RESOLVED',resolved_at=?,next_attempt_at=NULL,
                  lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND recovery_id=? AND state=? AND lock_version=?
                """, Timestamp.from(now), Timestamp.from(now), bytes(tenantId),
                bytes(state.get().recoveryId()), state.get().state(), state.get().lockVersion());
        if (changed != 1) throw new IllegalStateException("Incident Recovery resolution CAS lost");
    }

    private Optional<Inbox> findInbox(UUID tenantId, UUID inboxId, boolean lock) {
        requireTenant(tenantId);
        return jdbc.query(inboxSelect() + " WHERE tenant_id=? AND inbox_id=?"
                        + (lock ? " FOR UPDATE" : ""), INBOX_MAPPER,
                bytes(tenantId), bytes(inboxId)).stream().findFirst();
    }

    private static boolean sameSource(AcceptedIncidentMessage message, Inbox row) {
        return message.messageId().equals(row.messageId())
                && message.binding().contractRef().equals(row.contractRef())
                && row.contractVersion() == IncidentMessageContract.VERSION
                && Arrays.equals(message.businessIntentKey(), row.intent())
                && Arrays.equals(message.bodyBytes(), row.body())
                && Arrays.equals(message.bodySha256(), row.bodyHash())
                && Objects.equals(message.correlationId(), row.correlationId())
                && Objects.equals(message.causationId(), row.causationId())
                && Objects.equals(message.envelopeOccurredAt(), row.occurredAt());
    }

    private static UUID ticketId(AcceptedIncidentMessage message) {
        return stableUuid("FD-INCIDENT-TICKET-V1", message.tenantId(),
                message.incidentId(), message.createTicketIntentKey());
    }

    private static UUID inboxId(AcceptedIncidentMessage message) {
        return stableUuid("FD-INCIDENT-INBOX-V1", message.tenantId(), message.messageId());
    }

    private static UUID stableUuid(String namespace, Object... fields) {
        return IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(namespace, fields));
    }

    private static final UUID ZERO = new UUID(0L, 0L);

    private static String inboxSelect() {
        return """
                SELECT inbox_id,message_id,business_intent_key,contract_ref,contract_version,
                       body_bytes,body_sha256,correlation_id,causation_id,occurred_at,status,
                       result_type,result_id,attempt_count,last_error_code,lock_version
                FROM inbox_message
                """;
    }

    private static byte[] bytes(UUID value) { return Uuids.toBytes(value); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static void requireTenant(UUID tenantId) { TenantContextHolder.current().requireTenant(tenantId); }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Incident mutation requires a transaction");
        }
    }
    private static Instant instant(ResultSet rs, String field) throws SQLException {
        Timestamp value = rs.getTimestamp(field); return value == null ? null : value.toInstant();
    }
    private static Long nullableLong(ResultSet rs, String field) throws SQLException {
        long value = rs.getLong(field); return rs.wasNull() ? null : value;
    }

    private static final org.springframework.jdbc.core.RowMapper<Inbox> INBOX_MAPPER =
            (rs, n) -> new Inbox(Uuids.toUuid(rs.getBytes("inbox_id")),
                    Uuids.toUuid(rs.getBytes("message_id")), rs.getBytes("business_intent_key"),
                    rs.getString("contract_ref"), rs.getInt("contract_version"),
                    rs.getBytes("body_bytes"), rs.getBytes("body_sha256"),
                    Uuids.toUuid(rs.getBytes("correlation_id")), Uuids.toUuid(rs.getBytes("causation_id")),
                    instant(rs, "occurred_at"), rs.getString("status"), rs.getString("result_type"),
                    Uuids.toUuid(rs.getBytes("result_id")), rs.getInt("attempt_count"),
                    rs.getString("last_error_code"), rs.getLong("lock_version"));
    private static final org.springframework.jdbc.core.RowMapper<Policy> POLICY_MAPPER =
            (rs, n) -> new Policy(Uuids.toUuid(rs.getBytes("sla_policy_id")),
                    rs.getLong("first_response_seconds"), rs.getLong("resolution_seconds"),
                    nullableLong(rs, "first_response_reminder_before_seconds"),
                    nullableLong(rs, "resolution_reminder_before_seconds"));
    private static final org.springframework.jdbc.core.RowMapper<Mapping> MAPPING_MAPPER =
            (rs, n) -> new Mapping(Uuids.toUuid(rs.getBytes("incident_id")),
                    Uuids.toUuid(rs.getBytes("ticket_id")), rs.getBytes("creation_intent_key"),
                    instant(rs, "established_at"), rs.getString("origin_type"), rs.getString("status"),
                    rs.getBytes("ticket_intent"));
    private static final org.springframework.jdbc.core.RowMapper<TicketIdentity> TICKET_IDENTITY_MAPPER =
            (rs, n) -> new TicketIdentity(Uuids.toUuid(rs.getBytes("ticket_id")),
                    rs.getBytes("creation_intent_key"), rs.getString("origin_type"),
                    rs.getString("ticket_no"), rs.getString("subject"), rs.getString("description"),
                    rs.getString("status"));
    private static final org.springframework.jdbc.core.RowMapper<TimelineIdentity> TIMELINE_IDENTITY_MAPPER =
            (rs, n) -> new TimelineIdentity(Uuids.toUuid(rs.getBytes("timeline_id")),
                    rs.getString("event_type"), rs.getString("summary"), rs.getString("related_ref_type"),
                    Uuids.toUuid(rs.getBytes("related_ref_id")), rs.getString("actor_type"),
                    Uuids.toUuid(rs.getBytes("actor_id")), instant(rs, "occurred_at"),
                    Uuids.toUuid(rs.getBytes("correlation_id")), Uuids.toUuid(rs.getBytes("causation_id")),
                    rs.getBytes("effect_key"));
    private static final org.springframework.jdbc.core.RowMapper<ClockIdentity> CLOCK_IDENTITY_MAPPER =
            (rs, n) -> new ClockIdentity(Uuids.toUuid(rs.getBytes("sla_clock_id")),
                    Uuids.toUuid(rs.getBytes("sla_policy_id")), rs.getString("status"),
                    instant(rs, "started_at"), instant(rs, "deadline_at"), instant(rs, "reminder_due_at"),
                    rs.getLong("policy_duration_seconds"), nullableLong(rs, "policy_reminder_before_seconds"));
    private static final org.springframework.jdbc.core.RowMapper<OutboxIdentity> OUTBOX_IDENTITY_MAPPER =
            (rs, n) -> new OutboxIdentity(Uuids.toUuid(rs.getBytes("outbox_id")),
                    Uuids.toUuid(rs.getBytes("message_id")), rs.getBytes("publication_key"),
                    rs.getBytes("business_intent_key"), Uuids.toUuid(rs.getBytes("aggregate_id")),
                    rs.getString("contract_ref"), rs.getInt("contract_version"), rs.getBytes("body_bytes"),
                    rs.getBytes("body_sha256"), Uuids.toUuid(rs.getBytes("correlation_id")),
                    Uuids.toUuid(rs.getBytes("causation_id")));
    private static final org.springframework.jdbc.core.RowMapper<AuditIdentity> AUDIT_IDENTITY_MAPPER =
            (rs, n) -> new AuditIdentity(rs.getString("action_code"), rs.getString("target_type"),
                    Uuids.toUuid(rs.getBytes("target_id")), rs.getString("outcome"),
                    rs.getString("actor_type"), Uuids.toUuid(rs.getBytes("actor_id")),
                    rs.getString("reason"), rs.getString("error_code"),
                    Uuids.toUuid(rs.getBytes("correlation_id")), Uuids.toUuid(rs.getBytes("causation_id")));
    private static final org.springframework.jdbc.core.RowMapper<RecoveryIdentity> RECOVERY_IDENTITY_MAPPER =
            (rs, n) -> new RecoveryIdentity(Uuids.toUuid(rs.getBytes("recovery_id")),
                    rs.getBytes("business_intent_key"), rs.getString("state"), rs.getInt("attempt_count"),
                    rs.getString("last_error_code"), rs.getString("last_error_summary"));
    private static final org.springframework.jdbc.core.RowMapper<RecoveryState> RECOVERY_STATE_MAPPER =
            (rs, n) -> new RecoveryState(Uuids.toUuid(rs.getBytes("recovery_id")),
                    rs.getString("state"), rs.getLong("lock_version"));

    public enum DuplicateKind { NONE, TERMINAL, RECOVERABLE, CONFLICT }
    public record Duplicate(DuplicateKind kind, Inbox inbox, String conflictCode) {
        static Duplicate none() { return new Duplicate(DuplicateKind.NONE, null, null); }
    }
    public record Inbox(UUID inboxId, UUID messageId, byte[] intent, String contractRef,
                        int contractVersion, byte[] body, byte[] bodyHash, UUID correlationId,
                        UUID causationId, Instant occurredAt, String status, String resultType,
                        UUID resultId, int attemptCount, String lastErrorCode, long lockVersion) {
        public Inbox {
            intent = intent == null ? null : intent.clone(); body = body == null ? null : body.clone();
            bodyHash = bodyHash == null ? null : bodyHash.clone();
        }
        @Override public byte[] intent() { return intent == null ? null : intent.clone(); }
        @Override public byte[] body() { return body == null ? null : body.clone(); }
        @Override public byte[] bodyHash() { return bodyHash == null ? null : bodyHash.clone(); }
        /** Compatibility constructor retained for existing recovery fixtures. */
        public Inbox(UUID inboxId, UUID messageId, byte[] intent, String contractRef,
                     int contractVersion, byte[] body, byte[] bodyHash, String status,
                     String resultType, UUID resultId, int attemptCount,
                     String lastErrorCode, long lockVersion) {
            this(inboxId, messageId, intent, contractRef, contractVersion, body, bodyHash,
                    null, null, null, status, resultType, resultId, attemptCount,
                    lastErrorCode, lockVersion);
        }
    }
    public record Policy(UUID policyId, long firstResponseSeconds, long resolutionSeconds,
                         Long firstReminderSeconds, Long resolutionReminderSeconds) { }
    public record Mapping(UUID incidentId, UUID ticketId, byte[] creationIntent,
                          Instant establishedAt, String originType, String ticketStatus,
                          byte[] ticketIntent) {
        public Mapping { creationIntent = creationIntent == null ? null : creationIntent.clone(); ticketIntent = ticketIntent == null ? null : ticketIntent.clone(); }
        @Override public byte[] creationIntent() { return creationIntent == null ? null : creationIntent.clone(); }
        @Override public byte[] ticketIntent() { return ticketIntent == null ? null : ticketIntent.clone(); }
    }
    private record TicketIdentity(UUID ticketId, byte[] intent, String originType, String ticketNo,
                                  String subject, String description, String status) { }
    private record TimelineIdentity(UUID timelineId, String eventType, String summary,
                                    String relatedRefType, UUID relatedRefId, String actorType,
                                    UUID actorId, Instant occurredAt, UUID correlationId,
                                    UUID causationId, byte[] effectKey) { }
    private record ClockIdentity(UUID clockId, UUID policyId, String status, Instant startedAt,
                                 Instant deadlineAt, Instant reminderDueAt, long duration, Long reminder) { }
    private record OutboxIdentity(UUID outboxId, UUID messageId, byte[] publicationKey,
                                  byte[] intent, UUID aggregateId, String contractRef,
                                  int contractVersion, byte[] body, byte[] hash,
                                  UUID correlationId, UUID causationId) { }
    private record AuditIdentity(String action, String targetType, UUID targetId, String outcome,
                                 String actorType, UUID actorId, String reason, String error,
                                 UUID correlationId, UUID causationId) { }
    private record RecoveryIdentity(UUID recoveryId, byte[] intent, String state,
                                    int attemptCount, String errorCode, String summary) { }
    private record RecoveryState(UUID recoveryId, String state, long lockVersion) { }
    private static final class SetOf {
        private static final java.util.Set<String> OPEN_REPLAYING = java.util.Set.of("OPEN", "REPLAYING");
    }
}
