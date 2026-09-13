package com.flowdesk.ticket.incident;

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
import java.util.UUID;

/** Independent publisher repository selecting only the M7 authoritative result. */
public final class IncidentResultOutboxRepository {
    private static final int MAX_ATTEMPTS = IncidentMessageContract.OUTBOX_MAX_ATTEMPTS;
    private final JdbcTemplate jdbc;

    IncidentResultOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Candidate> candidates(Instant now, Instant cutoff) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(cutoff, "cutoff");
        return jdbc.query("""
                SELECT tenant_id,outbox_id,status,attempt_count,locked_by,correlation_id,lock_version
                FROM outbox_event
                WHERE contract_ref=? AND contract_version=? AND aggregate_type='INCIDENT'
                  AND attempt_count<=?
                  AND ((status='PENDING' AND next_attempt_at<=?)
                       OR (status='PUBLISHING' AND locked_at<=?))
                ORDER BY CASE WHEN status='PENDING' THEN next_attempt_at ELSE locked_at END,
                         tenant_id,outbox_id
                LIMIT ?
                """, CANDIDATE, IncidentMessageContract.ESTABLISHED_CONTRACT_REF,
                IncidentMessageContract.VERSION, MAX_ATTEMPTS, Timestamp.from(now),
                Timestamp.from(cutoff), IncidentMessageContract.OUTBOX_MAX_CLAIMS);
    }

    public Optional<Claimed> claim(Candidate candidate, String token, Instant now,
                                   Instant cutoff) {
        requireTx();
        requireTenant(candidate.tenantId());
        requireToken(token);
        int changed;
        if ("PENDING".equals(candidate.status())) {
            changed = jdbc.update("""
                    UPDATE outbox_event
                    SET status='PUBLISHING',attempt_count=attempt_count+1,
                        locked_by=?,locked_at=?,lock_version=lock_version+1,
                        updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                    WHERE tenant_id=? AND outbox_id=? AND status='PENDING'
                      AND attempt_count=? AND attempt_count<? AND lock_version=?
                      AND next_attempt_at<=?
                    """, token, ts(now), ts(now), Uuids.toBytes(candidate.tenantId()),
                    Uuids.toBytes(candidate.outboxId()), candidate.attemptCount(), MAX_ATTEMPTS,
                    candidate.lockVersion(), ts(now));
        }
        else if ("PUBLISHING".equals(candidate.status()) && candidate.lockedBy() != null) {
            changed = jdbc.update("""
                    UPDATE outbox_event
                    SET attempt_count=attempt_count+1,locked_by=?,locked_at=?,
                        lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',
                        updated_by_id=NULL
                    WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING'
                      AND attempt_count=? AND attempt_count<? AND locked_by=?
                      AND lock_version=? AND locked_at<=?
                    """, token, ts(now), ts(now), Uuids.toBytes(candidate.tenantId()),
                    Uuids.toBytes(candidate.outboxId()), candidate.attemptCount(), MAX_ATTEMPTS,
                    candidate.lockedBy(), candidate.lockVersion(), ts(cutoff));
        }
        else {
            return Optional.empty();
        }
        if (changed != 1) return Optional.empty();
        return claimed(candidate.tenantId(), candidate.outboxId(), token,
                candidate.attemptCount() + 1, candidate.lockVersion() + 1);
    }

    /** Takes over an exhausted row without consuming another broker attempt. */
    public Optional<Claimed> claimRecovery(Candidate candidate, String token, Instant now,
                                           Instant cutoff) {
        requireTx();
        requireTenant(candidate.tenantId());
        requireToken(token);
        if (candidate.attemptCount() != MAX_ATTEMPTS) return Optional.empty();
        int changed;
        if ("PENDING".equals(candidate.status())) {
            changed = jdbc.update("""
                    UPDATE outbox_event
                    SET status='PUBLISHING',locked_by=?,locked_at=?,lock_version=lock_version+1,
                        updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                    WHERE tenant_id=? AND outbox_id=? AND status='PENDING'
                      AND attempt_count=? AND lock_version=? AND next_attempt_at<=?
                    """, token, ts(now), ts(now), Uuids.toBytes(candidate.tenantId()),
                    Uuids.toBytes(candidate.outboxId()), MAX_ATTEMPTS, candidate.lockVersion(), ts(now));
        }
        else if ("PUBLISHING".equals(candidate.status()) && candidate.lockedBy() != null) {
            changed = jdbc.update("""
                    UPDATE outbox_event
                    SET locked_by=?,locked_at=?,lock_version=lock_version+1,
                        updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                    WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING'
                      AND attempt_count=? AND locked_by=? AND lock_version=? AND locked_at<=?
                    """, token, ts(now), ts(now), Uuids.toBytes(candidate.tenantId()),
                    Uuids.toBytes(candidate.outboxId()), MAX_ATTEMPTS, candidate.lockedBy(),
                    candidate.lockVersion(), ts(cutoff));
        }
        else {
            return Optional.empty();
        }
        if (changed != 1) return Optional.empty();
        return claimed(candidate.tenantId(), candidate.outboxId(), token,
                MAX_ATTEMPTS, candidate.lockVersion() + 1);
    }

    private Optional<Claimed> claimed(UUID tenantId, UUID outboxId, String token,
                                      int attempt, long version) {
        return jdbc.query("""
                SELECT tenant_id,outbox_id,message_id,publication_key,business_intent_key,
                       aggregate_id,body_bytes,body_sha256,correlation_id,causation_id,
                       attempt_count,locked_by,locked_at,lock_version,created_at
                FROM outbox_event
                WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING'
                  AND attempt_count=? AND locked_by=? AND lock_version=?
                """, CLAIMED, Uuids.toBytes(tenantId), Uuids.toBytes(outboxId),
                attempt, token, version).stream().findFirst();
    }

    public boolean published(Claimed row, Instant now, Instant cutoff) {
        requireTx();
        requireTenant(row.tenantId());
        int changed = jdbc.update("""
                UPDATE outbox_event
                SET status='PUBLISHED',locked_by=NULL,locked_at=NULL,last_error_code=NULL,
                    broker_confirmed_at=?,lock_version=lock_version+1,updated_at=?,
                    updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING'
                  AND attempt_count=? AND locked_by=? AND lock_version=? AND locked_at>?
                """, ts(now), ts(now), Uuids.toBytes(row.tenantId()), Uuids.toBytes(row.outboxId()),
                row.attemptCount(), row.token(), row.lockVersion(), ts(cutoff));
        if (changed != 1) return false;
        resolveReplayRecovery(row, now);
        return true;
    }

    private void resolveReplayRecovery(Claimed row, Instant now) {
        Optional<RecoveryState> recovery = findRecovery(row.tenantId(), row.outboxId(), true);
        if (recovery.isEmpty() || "RESOLVED".equals(recovery.get().state())) return;
        RecoveryState current = recovery.get();
        if (!Arrays.equals(row.intent(), current.intent()) || !"REPLAYING".equals(current.state())) {
            throw new IllegalStateException("Incident result Recovery state/content conflict");
        }
        int changed = jdbc.update("""
                UPDATE message_recovery
                SET state='RESOLVED',resolved_at=?,next_attempt_at=NULL,
                    lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND recovery_id=? AND state='REPLAYING' AND lock_version=?
                """, ts(now), ts(now), Uuids.toBytes(row.tenantId()),
                Uuids.toBytes(current.recoveryId()), current.lockVersion());
        if (changed != 1) throw new IllegalStateException("Incident result Recovery resolution CAS lost");
    }

    public boolean pending(Claimed row, Instant now, Instant cutoff, Instant next, String code) {
        requireTx();
        requireTenant(row.tenantId());
        int changed = jdbc.update("""
                UPDATE outbox_event
                SET status='PENDING',next_attempt_at=?,locked_by=NULL,locked_at=NULL,
                    last_error_code=?,broker_confirmed_at=NULL,lock_version=lock_version+1,
                    updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING'
                  AND attempt_count=? AND locked_by=? AND lock_version=? AND locked_at>?
                """, ts(next), safeCode(code), ts(now), Uuids.toBytes(row.tenantId()),
                Uuids.toBytes(row.outboxId()), row.attemptCount(), row.token(), row.lockVersion(),
                ts(cutoff));
        return changed == 1;
    }

    /** Moves the exhausted eighth attempt to a deterministic Recovery fact. */
    public boolean recover(Claimed row, Instant now, Instant cutoff, String code) {
        requireTx();
        requireTenant(row.tenantId());
        if (row.attemptCount() != MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Incident result recovery requires the eighth attempt");
        }
        String safeCode = safeCode(code);
        int changed = jdbc.update("""
                UPDATE outbox_event
                SET status='RECOVERY_REQUIRED',next_attempt_at=?,locked_by=NULL,locked_at=NULL,
                    last_error_code=?,broker_confirmed_at=NULL,lock_version=lock_version+1,
                    updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING'
                  AND attempt_count=? AND locked_by=? AND lock_version=? AND locked_at>?
                """, ts(now), safeCode, ts(now), Uuids.toBytes(row.tenantId()),
                Uuids.toBytes(row.outboxId()), MAX_ATTEMPTS, row.token(), row.lockVersion(), ts(cutoff));
        if (changed != 1) return false;

        String summary = "Incident result publication exhausted bounded retries.";
        ensureRecovery(row, safeCode, summary, now);
        UUID auditId = stableUuid("FD-INCIDENT-RESULT-OUTBOX-RECOVERY-AUDIT-V1",
                row.tenantId(), row.outboxId(), safeCode);
        insertAuditOnce(row, auditId, safeCode, summary, now);
        return true;
    }

    private void ensureRecovery(Claimed row, String code, String summary, Instant now) {
        UUID recoveryId = stableUuid("FD-INCIDENT-RESULT-OUTBOX-RECOVERY-V1",
                row.tenantId(), row.outboxId());
        Optional<RecoveryState> existing = findRecovery(row.tenantId(), row.outboxId(), true);
        if (existing.isPresent()) {
            RecoveryState state = existing.get();
            if (!recoveryId.equals(state.recoveryId()) || !Arrays.equals(row.intent(), state.intent())
                    || state.attemptCount() != MAX_ATTEMPTS
                    || !("OPEN".equals(state.state()) || "REPLAYING".equals(state.state()))
                    || !Objects.equals(code, state.errorCode())
                    || !Objects.equals(summary, state.summary())) {
                throw new IllegalStateException("Incident result Recovery identity/content conflict");
            }
            if ("REPLAYING".equals(state.state())) {
                int reopened = jdbc.update("""
                        UPDATE message_recovery
                        SET state='OPEN',last_error_code=?,last_error_summary=?,resolved_at=NULL,
                            next_attempt_at=NULL,lock_version=lock_version+1,updated_at=?,
                            updated_by_type='SERVICE',updated_by_id=NULL
                        WHERE tenant_id=? AND recovery_id=? AND state='REPLAYING' AND lock_version=?
                        """, code, summary, ts(now), Uuids.toBytes(row.tenantId()),
                        Uuids.toBytes(recoveryId), state.lockVersion());
                if (reopened != 1) throw new IllegalStateException("Incident result Recovery reopen CAS lost");
            }
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO message_recovery
                      (tenant_id,recovery_id,direction,source_record_id,business_intent_key,state,
                       attempt_count,next_attempt_at,last_error_code,last_error_summary,dead_lettered_at,
                       recovery_required_at,replay_count,last_replay_correlation_id,resolved_at,
                       lock_version,created_at,updated_at,updated_by_type,updated_by_id)
                    VALUES (?,?,'OUTBOX',?,?,'OPEN',?,NULL,?,?,NULL,?,0,NULL,NULL,0,?,?,'SERVICE',NULL)
                    """, Uuids.toBytes(row.tenantId()), Uuids.toBytes(recoveryId),
                    Uuids.toBytes(row.outboxId()), row.intent(), MAX_ATTEMPTS, code, summary,
                    ts(now), ts(now), ts(now));
        }
        catch (DuplicateKeyException duplicate) {
            RecoveryState raced = findRecovery(row.tenantId(), row.outboxId(), true)
                    .orElseThrow(() -> duplicate);
            if (!recoveryId.equals(raced.recoveryId()) || !Arrays.equals(row.intent(), raced.intent())
                    || raced.attemptCount() != MAX_ATTEMPTS || !"OPEN".equals(raced.state())
                    || !Objects.equals(code, raced.errorCode())
                    || !Objects.equals(summary, raced.summary())) {
                throw new IllegalStateException("Incident result Recovery identity/content conflict", duplicate);
            }
        }
    }

    private void insertAuditOnce(Claimed row, UUID auditId, String code,
                                 String summary, Instant now) {
        Optional<AuditState> existing = findAudit(row.tenantId(), auditId);
        if (existing.isPresent()) {
            validateAudit(existing.get(), row, code, summary);
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO audit_record
                      (tenant_id,audit_id,action_code,target_type,target_id,outcome,actor_type,actor_id,
                       reason,error_code,correlation_id,causation_id,occurred_at)
                    VALUES (?,?,'INCIDENT_RESULT_OUTBOX_RECOVERY_REQUIRED','MESSAGE_RECOVERY',?,
                            'FAILED','SERVICE',NULL,?,?,?, ?,?)
                    """, Uuids.toBytes(row.tenantId()), Uuids.toBytes(auditId),
                    Uuids.toBytes(row.outboxId()), summary, code,
                    Uuids.toBytes(row.correlationId()), Uuids.toBytes(row.causationId()), ts(now));
        }
        catch (DuplicateKeyException duplicate) {
            AuditState raced = findAudit(row.tenantId(), auditId).orElseThrow(() -> duplicate);
            validateAudit(raced, row, code, summary);
        }
    }

    private Optional<RecoveryState> findRecovery(UUID tenantId, UUID outboxId, boolean lock) {
        return jdbc.query("""
                SELECT recovery_id,business_intent_key,state,attempt_count,last_error_code,
                       last_error_summary,lock_version
                FROM message_recovery
                WHERE tenant_id=? AND direction='OUTBOX' AND source_record_id=?
                """ + (lock ? " FOR UPDATE" : ""), RECOVERY_MAPPER,
                Uuids.toBytes(tenantId), Uuids.toBytes(outboxId)).stream().findFirst();
    }

    private Optional<AuditState> findAudit(UUID tenantId, UUID auditId) {
        return jdbc.query("""
                SELECT action_code,target_type,target_id,outcome,actor_type,actor_id,
                       reason,error_code,correlation_id,causation_id
                FROM audit_record WHERE tenant_id=? AND audit_id=?
                """, AUDIT_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(auditId))
                .stream().findFirst();
    }

    private static void validateAudit(AuditState audit, Claimed row, String code, String summary) {
        if (!"INCIDENT_RESULT_OUTBOX_RECOVERY_REQUIRED".equals(audit.action())
                || !"MESSAGE_RECOVERY".equals(audit.targetType())
                || !row.outboxId().equals(audit.targetId()) || !"FAILED".equals(audit.outcome())
                || !"SERVICE".equals(audit.actorType()) || audit.actorId() != null
                || !Objects.equals(summary, audit.reason()) || !Objects.equals(code, audit.errorCode())
                || !Objects.equals(row.correlationId(), audit.correlationId())
                || !Objects.equals(row.causationId(), audit.causationId())) {
            throw new IllegalStateException("Incident result Recovery audit identity/content conflict");
        }
    }

    private static UUID stableUuid(String namespace, Object... fields) {
        return IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(namespace, fields));
    }

    private static String safeCode(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_:.]{0,95}")
                ? value : "BROKER_RESULT_UNKNOWN";
    }

    private static void requireToken(String token) {
        if (token == null || token.isBlank() || token.length() > 96) {
            throw new IllegalArgumentException("Outbox claim token must be 1..96 characters");
        }
    }

    private static void requireTx() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Incident result Outbox mutation requires a transaction");
        }
    }

    private static void requireTenant(UUID tenantId) {
        TenantContextHolder.current().requireTenant(tenantId);
    }

    private static Timestamp ts(Instant value) {
        return Timestamp.from(Objects.requireNonNull(value, "timestamp"));
    }

    private static Instant instant(ResultSet rs, String field) throws SQLException {
        Timestamp value = rs.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }

    private static final org.springframework.jdbc.core.RowMapper<Candidate> CANDIDATE =
            (rs, n) -> new Candidate(Uuids.toUuid(rs.getBytes("tenant_id")),
                    Uuids.toUuid(rs.getBytes("outbox_id")), rs.getString("status"),
                    rs.getInt("attempt_count"), rs.getString("locked_by"),
                    Uuids.toUuid(rs.getBytes("correlation_id")), rs.getLong("lock_version"));
    private static final org.springframework.jdbc.core.RowMapper<Claimed> CLAIMED =
            (rs, n) -> new Claimed(Uuids.toUuid(rs.getBytes("tenant_id")),
                    Uuids.toUuid(rs.getBytes("outbox_id")), Uuids.toUuid(rs.getBytes("message_id")),
                    rs.getBytes("publication_key"), rs.getBytes("business_intent_key"),
                    Uuids.toUuid(rs.getBytes("aggregate_id")), rs.getBytes("body_bytes"),
                    rs.getBytes("body_sha256"), Uuids.toUuid(rs.getBytes("correlation_id")),
                    Uuids.toUuid(rs.getBytes("causation_id")), rs.getInt("attempt_count"),
                    rs.getString("locked_by"), instant(rs, "locked_at"), rs.getLong("lock_version"),
                    instant(rs, "created_at"));
    private static final org.springframework.jdbc.core.RowMapper<RecoveryState> RECOVERY_MAPPER =
            (rs, n) -> new RecoveryState(Uuids.toUuid(rs.getBytes("recovery_id")),
                    rs.getBytes("business_intent_key"), rs.getString("state"),
                    rs.getInt("attempt_count"), rs.getString("last_error_code"),
                    rs.getString("last_error_summary"), rs.getLong("lock_version"));
    private static final org.springframework.jdbc.core.RowMapper<AuditState> AUDIT_MAPPER =
            (rs, n) -> new AuditState(rs.getString("action_code"), rs.getString("target_type"),
                    Uuids.toUuid(rs.getBytes("target_id")), rs.getString("outcome"),
                    rs.getString("actor_type"), Uuids.toUuid(rs.getBytes("actor_id")),
                    rs.getString("reason"), rs.getString("error_code"),
                    Uuids.toUuid(rs.getBytes("correlation_id")),
                    Uuids.toUuid(rs.getBytes("causation_id")));

    public record Candidate(UUID tenantId, UUID outboxId, String status, int attemptCount,
                            String lockedBy, UUID correlationId, long lockVersion) { }

    public record Claimed(UUID tenantId, UUID outboxId, UUID messageId, byte[] publicationKey,
                          byte[] intent, UUID incidentId, byte[] body, byte[] hash,
                          UUID correlationId, UUID causationId, int attemptCount, String token,
                          Instant lockedAt, long lockVersion, Instant createdAt) {
        public Claimed {
            publicationKey = publicationKey.clone();
            intent = intent.clone();
            body = body.clone();
            hash = hash.clone();
        }
        @Override public byte[] publicationKey() { return publicationKey.clone(); }
        @Override public byte[] intent() { return intent.clone(); }
        @Override public byte[] body() { return body.clone(); }
        @Override public byte[] hash() { return hash.clone(); }
    }

    private record RecoveryState(UUID recoveryId, byte[] intent, String state,
                                 int attemptCount, String errorCode, String summary,
                                 long lockVersion) {
        private RecoveryState {
            intent = intent == null ? null : intent.clone();
        }
        @Override public byte[] intent() { return intent == null ? null : intent.clone(); }
    }

    private record AuditState(String action, String targetType, UUID targetId, String outcome,
                              String actorType, UUID actorId, String reason, String errorCode,
                              UUID correlationId, UUID causationId) { }
}
