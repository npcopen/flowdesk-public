package com.flowdesk.workflow.notification;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.notification.NotificationWireCodec;
import com.flowdesk.common.tenant.TenantContextHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Tenant-first persistence boundary for Workflow -> Notification Outbox rows. */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkflowNotificationOutboxRepository {
    private static final NotificationEventContract.Binding BINDING =
            NotificationEventContract.WORKFLOW;
    private static final int MAX_ATTEMPTS = NotificationEventContract.OUTBOX_MAX_ATTEMPTS;
    private static final int MAX_CLAIMS = NotificationEventContract.OUTBOX_MAX_CLAIMS;
    private final JdbcTemplate jdbc;

    public WorkflowNotificationOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Candidate> findCandidates(Instant now, Instant leaseCutoff, int limit) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseCutoff, "leaseCutoff");
        if (limit < 1 || limit > MAX_CLAIMS) {
            throw new IllegalArgumentException("limit must be 1..50");
        }
        return jdbc.query("""
                SELECT tenant_id,outbox_id,status,attempt_count,locked_by,
                       correlation_id,lock_version
                FROM outbox_event
                WHERE contract_ref=? AND contract_version=? AND aggregate_type=?
                  AND attempt_count<=?
                  AND ((status='PENDING' AND next_attempt_at<=?)
                    OR (status='PUBLISHING' AND locked_at<=?))
                ORDER BY CASE WHEN status='PENDING' THEN next_attempt_at ELSE locked_at END,
                         tenant_id,outbox_id
                LIMIT ?
                """, CANDIDATE_MAPPER, BINDING.contractRef(), NotificationEventContract.VERSION,
                "NOTIFICATION_REQUEST", MAX_ATTEMPTS, ts(now), ts(leaseCutoff), limit);
    }

    public Optional<Claimed> claimForSend(Candidate candidate, String token,
                                          Instant now, Instant leaseCutoff) {
        requireTransaction();
        requireTenant(candidate.tenantId());
        requireToken(token);
        int updated;
        if ("PENDING".equals(candidate.status())) {
            updated = jdbc.update("""
                    UPDATE outbox_event
                    SET status='PUBLISHING',attempt_count=attempt_count+1,locked_by=?,locked_at=?,
                        lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                    WHERE tenant_id=? AND outbox_id=? AND status='PENDING'
                      AND attempt_count=? AND attempt_count<? AND lock_version=? AND next_attempt_at<=?
                    """, token, ts(now), ts(now), bytes(candidate.tenantId()), bytes(candidate.outboxId()),
                    candidate.attemptCount(), MAX_ATTEMPTS, candidate.lockVersion(), ts(now));
        }
        else if ("PUBLISHING".equals(candidate.status()) && candidate.lockedBy() != null) {
            updated = jdbc.update("""
                    UPDATE outbox_event
                    SET attempt_count=attempt_count+1,locked_by=?,locked_at=?,lock_version=lock_version+1,
                        updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                    WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING'
                      AND attempt_count=? AND attempt_count<? AND locked_by=? AND lock_version=? AND locked_at<=?
                    """, token, ts(now), ts(now), bytes(candidate.tenantId()), bytes(candidate.outboxId()),
                    candidate.attemptCount(), MAX_ATTEMPTS, candidate.lockedBy(), candidate.lockVersion(),
                    ts(leaseCutoff));
        }
        else {
            return Optional.empty();
        }
        if (updated != 1) return Optional.empty();
        return findClaimed(candidate.tenantId(), candidate.outboxId(), token,
                candidate.attemptCount() + 1, candidate.lockVersion() + 1);
    }

    /** Takes over an exhausted row without consuming another broker attempt. */
    public Optional<Claimed> claimForRecovery(Candidate candidate, String token,
                                               Instant now, Instant leaseCutoff) {
        requireTransaction();
        requireTenant(candidate.tenantId());
        requireToken(token);
        if (candidate.attemptCount() != MAX_ATTEMPTS) return Optional.empty();
        int updated;
        if ("PENDING".equals(candidate.status())) {
            updated = jdbc.update("""
                    UPDATE outbox_event SET status='PUBLISHING',locked_by=?,locked_at=?,
                        lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                    WHERE tenant_id=? AND outbox_id=? AND status='PENDING' AND attempt_count=?
                      AND lock_version=? AND next_attempt_at<=?
                    """, token, ts(now), ts(now), bytes(candidate.tenantId()), bytes(candidate.outboxId()),
                    MAX_ATTEMPTS, candidate.lockVersion(), ts(now));
        }
        else if ("PUBLISHING".equals(candidate.status()) && candidate.lockedBy() != null) {
            updated = jdbc.update("""
                    UPDATE outbox_event SET locked_by=?,locked_at=?,lock_version=lock_version+1,
                        updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                    WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING' AND attempt_count=?
                      AND locked_by=? AND lock_version=? AND locked_at<=?
                    """, token, ts(now), ts(now), bytes(candidate.tenantId()), bytes(candidate.outboxId()),
                    MAX_ATTEMPTS, candidate.lockedBy(), candidate.lockVersion(), ts(leaseCutoff));
        }
        else return Optional.empty();
        if (updated != 1) return Optional.empty();
        return findClaimed(candidate.tenantId(), candidate.outboxId(), token,
                MAX_ATTEMPTS, candidate.lockVersion() + 1);
    }

    public boolean markPublished(Claimed row, Instant now, Instant leaseCutoff) {
        requireTransaction();
        requireTenant(row.tenantId());
        int updated = jdbc.update("""
                UPDATE outbox_event SET status='PUBLISHED',locked_by=NULL,locked_at=NULL,
                    last_error_code=NULL,broker_confirmed_at=?,lock_version=lock_version+1,
                    updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING' AND attempt_count=?
                  AND locked_by=? AND lock_version=? AND locked_at>?
                """, ts(now), ts(now), bytes(row.tenantId()), bytes(row.outboxId()), row.attemptCount(),
                row.claimToken(), row.lockVersion(), ts(leaseCutoff));
        if (updated == 1) resolveReplay(row.tenantId(), row.outboxId(), now);
        return updated == 1;
    }

    public boolean markPending(Claimed row, Instant now, Instant leaseCutoff,
                               Instant nextAttemptAt, String errorCode) {
        requireTransaction();
        requireTenant(row.tenantId());
        if (row.attemptCount() < 1 || row.attemptCount() >= MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Only attempts 1..7 may return to PENDING");
        }
        return jdbc.update("""
                UPDATE outbox_event SET status='PENDING',next_attempt_at=?,locked_by=NULL,locked_at=NULL,
                    last_error_code=?,broker_confirmed_at=NULL,lock_version=lock_version+1,
                    updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING' AND attempt_count=?
                  AND locked_by=? AND lock_version=? AND locked_at>?
                """, ts(nextAttemptAt), errorCode, ts(now), bytes(row.tenantId()), bytes(row.outboxId()),
                row.attemptCount(), row.claimToken(), row.lockVersion(), ts(leaseCutoff)) == 1;
    }

    /** Moves an exhausted row and creates its Recovery fact atomically. */
    public boolean moveToRecovery(Claimed row, Instant now, Instant leaseCutoff,
                                  String errorCode, String summary, UUID recoveryId) {
        requireTransaction();
        requireTenant(row.tenantId());
        if (row.attemptCount() != MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Recovery requires attempt 8");
        }
        return moveAndRecord(row, now, leaseCutoff, errorCode, summary, recoveryId);
    }

    /** Deterministic contract corruption is terminal for publishing, never retried forever. */
    public boolean rejectContract(Claimed row, Instant now, Instant leaseCutoff,
                                  String errorCode, String summary, UUID recoveryId) {
        requireTransaction();
        requireTenant(row.tenantId());
        return moveAndRecord(row, now, leaseCutoff, errorCode, summary, recoveryId);
    }

    private boolean moveAndRecord(Claimed row, Instant now, Instant leaseCutoff,
                                  String errorCode, String summary, UUID recoveryId) {
        String code = validCode(errorCode);
        int updated = jdbc.update("""
                UPDATE outbox_event SET status='RECOVERY_REQUIRED',next_attempt_at=?,locked_by=NULL,
                    locked_at=NULL,last_error_code=?,broker_confirmed_at=NULL,lock_version=lock_version+1,
                    updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING' AND attempt_count=?
                  AND locked_by=? AND lock_version=? AND locked_at>?
                """, ts(now), code, ts(now), bytes(row.tenantId()), bytes(row.outboxId()),
                row.attemptCount(), row.claimToken(), row.lockVersion(), ts(leaseCutoff));
        if (updated != 1) return false;
        // Recovery identity is deterministic for the source Outbox row; a
        // random UUID would split duplicate exhaustion into multiple facts.
        ensureRecovery(row, now, code, sanitize(summary), recoveryId(row));
        jdbc.update("""
                INSERT INTO audit_record
                  (tenant_id,audit_id,action_code,target_type,target_id,outcome,actor_type,actor_id,
                   reason,error_code,correlation_id,causation_id,occurred_at)
                VALUES (?,?,?,'OUTBOX_EVENT',?,'FAILED','SERVICE',NULL,?,?,?, ?,?)
                """, bytes(row.tenantId()), bytes(UUID.randomUUID()),
                "WORKFLOW_NOTIFICATION_OUTBOX_RECOVERY_REQUIRED", bytes(row.outboxId()),
                sanitize(summary), code, bytes(row.correlationId()), bytes(row.causationId()), ts(now));
        return true;
    }

    private void ensureRecovery(Claimed row, Instant now, String code, String summary, UUID recoveryId) {
        try {
            jdbc.update("""
                    INSERT INTO message_recovery
                      (tenant_id,recovery_id,direction,source_record_id,business_intent_key,state,
                       attempt_count,next_attempt_at,last_error_code,last_error_summary,dead_lettered_at,
                       recovery_required_at,replay_count,last_replayed_at,last_replayed_by_type,
                       last_replayed_by_id,last_replay_reason,resolved_at,lock_version,created_at,
                       updated_at,updated_by_type,updated_by_id)
                    VALUES (?,?,'OUTBOX',?,?,'OPEN',?,NULL,?,?,NULL,?,0,NULL,NULL,NULL,NULL,NULL,
                            NULL,0,?,?,'SERVICE',NULL)
                    """, bytes(row.tenantId()), bytes(recoveryId), bytes(row.outboxId()),
                    row.businessIntentKey(), row.attemptCount(), code, summary, ts(now), ts(now), ts(now));
        }
        catch (DuplicateKeyException duplicate) {
            Recovery existing = jdbc.query("""
                    SELECT business_intent_key,state,attempt_count FROM message_recovery
                    WHERE tenant_id=? AND direction='OUTBOX' AND source_record_id=?
                    """, (rs, n) -> new Recovery(rs.getBytes(1), rs.getString(2), rs.getInt(3)),
                    bytes(row.tenantId()), bytes(row.outboxId())).stream().findFirst().orElseThrow(() -> duplicate);
            if (!Arrays.equals(existing.intent(), row.businessIntentKey())
                    || !"OPEN".equals(existing.state()) || existing.attemptCount() != row.attemptCount()) {
                throw new IllegalStateException("Conflicting Workflow Notification recovery", duplicate);
            }
        }
    }

    private void resolveReplay(UUID tenantId, UUID outboxId, Instant now) {
        jdbc.update("""
                UPDATE message_recovery SET state='RESOLVED',resolved_at=?,next_attempt_at=NULL,
                    lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND direction='OUTBOX' AND source_record_id=? AND state='REPLAYING'
                """, ts(now), ts(now), bytes(tenantId), bytes(outboxId));
    }

    private Optional<Claimed> findClaimed(UUID tenantId, UUID outboxId, String token,
                                          int attempts, long lockVersion) {
        return jdbc.query("""
                SELECT tenant_id,outbox_id,message_id,publication_key,business_intent_key,aggregate_type,
                       aggregate_id,contract_ref,contract_version,body_bytes,body_sha256,correlation_id,
                       causation_id,attempt_count,locked_by,locked_at,lock_version,created_at
                FROM outbox_event WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING'
                  AND attempt_count=? AND locked_by=? AND lock_version=?
                """, CLAIMED_MAPPER, bytes(tenantId), bytes(outboxId), attempts, token, lockVersion)
                .stream().findFirst();
    }

    /** Stable Recovery primary key for one Workflow Notification Outbox source. */
    public static UUID recoveryId(Claimed row) {
        Objects.requireNonNull(row, "row");
        return NotificationWireCodec.uuidFromKey(NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-RECOVERY-V1", row.tenantId(), row.outboxId(), "OUTBOX"));
    }

    private static String validCode(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,95}")) return "BROKER_RESULT_UNKNOWN";
        return code;
    }

    private static String sanitize(String value) {
        if (value == null) return null;
        String result = value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return result.isEmpty() ? null : result.substring(0, Math.min(1000, result.length()));
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Workflow Notification Outbox mutation requires a transaction");
        }
    }

    private static void requireTenant(UUID tenantId) {
        TenantContextHolder.current().requireTenant(tenantId);
    }

    private static void requireToken(String token) {
        if (token == null || token.isBlank() || token.length() > 96) {
            throw new IllegalArgumentException("claim token must be 1..96 characters");
        }
    }

    private static byte[] bytes(UUID value) {
        return value == null ? null : ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        if (value == null) return null;
        if (value.length != 16) throw new IllegalArgumentException("UUID column must be 16 bytes");
        ByteBuffer b = ByteBuffer.wrap(value); return new UUID(b.getLong(), b.getLong());
    }

    private static Timestamp ts(Instant value) { return Timestamp.from(Objects.requireNonNull(value)); }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }

    private static final org.springframework.jdbc.core.RowMapper<Candidate> CANDIDATE_MAPPER =
            (rs, n) -> new Candidate(uuid(rs.getBytes("tenant_id")), uuid(rs.getBytes("outbox_id")),
                    rs.getString("status"), rs.getInt("attempt_count"), rs.getString("locked_by"),
                    uuid(rs.getBytes("correlation_id")), rs.getLong("lock_version"));
    private static final org.springframework.jdbc.core.RowMapper<Claimed> CLAIMED_MAPPER =
            (rs, n) -> new Claimed(uuid(rs.getBytes("tenant_id")), uuid(rs.getBytes("outbox_id")),
                    uuid(rs.getBytes("message_id")), rs.getBytes("publication_key"),
                    rs.getBytes("business_intent_key"), rs.getString("aggregate_type"),
                    uuid(rs.getBytes("aggregate_id")), rs.getString("contract_ref"),
                    rs.getInt("contract_version"), rs.getBytes("body_bytes"), rs.getBytes("body_sha256"),
                    uuid(rs.getBytes("correlation_id")), uuid(rs.getBytes("causation_id")),
                    rs.getInt("attempt_count"), rs.getString("locked_by"), instant(rs, "locked_at"),
                    rs.getLong("lock_version"), instant(rs, "created_at"));

    public record Candidate(UUID tenantId, UUID outboxId, String status, int attemptCount,
                            String lockedBy, UUID correlationId, long lockVersion) { }

    public record Claimed(UUID tenantId, UUID outboxId, UUID messageId, byte[] publicationKey,
                          byte[] businessIntentKey, String aggregateType, UUID aggregateId,
                          String contractRef, int contractVersion, byte[] bodyBytes, byte[] bodySha256,
                          UUID correlationId, UUID causationId, int attemptCount, String claimToken,
                          Instant claimedAt, long lockVersion, Instant createdAt) {
        public Claimed {
            publicationKey = publicationKey.clone(); businessIntentKey = businessIntentKey.clone();
            bodyBytes = bodyBytes.clone(); bodySha256 = bodySha256.clone();
        }
        @Override public byte[] publicationKey() { return publicationKey.clone(); }
        @Override public byte[] businessIntentKey() { return businessIntentKey.clone(); }
        @Override public byte[] bodyBytes() { return bodyBytes.clone(); }
        @Override public byte[] bodySha256() { return bodySha256.clone(); }
    }

    private record Recovery(byte[] intent, String state, int attemptCount) { }
}
