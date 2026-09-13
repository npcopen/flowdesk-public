package com.flowdesk.ticket.notification;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.notification.NotificationWireCodec;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.ticket.repository.TicketRepository.Uuids;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
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

/** Tenant-first persistence boundary for Ticket -> Notification Outbox rows. */
@Repository
public class TicketNotificationOutboxRepository {
    private static final NotificationEventContract.Binding BINDING = NotificationEventContract.TICKET;
    private final JdbcTemplate jdbc;

    public TicketNotificationOutboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Insert one immutable notification intent and converge a duplicate key only
     * when its stable publication identity and payload are identical. The first
     * commit owns correlation/causation metadata: a later idempotent replay may
     * arrive under a new request correlation but must retain the original trace.
     * This is used by reverse-event recovery and existing SLA-trigger replay.
     */
    public void insertIntent(UUID tenantId, TicketNotificationIntent.Intent intent) {
        requireTransaction();
        requireTenant(tenantId);
        try {
            jdbc.update("""
                    INSERT INTO outbox_event
                      (tenant_id,outbox_id,message_id,publication_key,business_intent_key,
                       aggregate_type,aggregate_id,contract_ref,contract_version,body_bytes,
                       body_sha256,correlation_id,causation_id,status,attempt_count,next_attempt_at,
                       locked_by,locked_at,last_error_code,broker_confirmed_at,lock_version,
                       created_at,created_by_type,created_by_id,updated_at,updated_by_type,updated_by_id)
                    VALUES (?,?,?,?,?,'NOTIFICATION_REQUEST',?,?,?, ?,?,?,?,'PENDING',0,?,
                            NULL,NULL,NULL,NULL,0,?,'SERVICE',NULL,?,'SERVICE',NULL)
                    """, Uuids.toBytes(tenantId), Uuids.toBytes(intent.outboxId()),
                    Uuids.toBytes(intent.messageId()), intent.publicationKey(), intent.businessIntentKey(),
                    Uuids.toBytes(intent.sourceRefId()), BINDING.contractRef(), NotificationEventContract.VERSION,
                    intent.bodyBytes(), intent.bodySha256(), Uuids.toBytes(intent.correlationId()),
                    Uuids.toBytes(intent.causationId()), ts(intent.occurredAt()), ts(intent.occurredAt()),
                    ts(intent.occurredAt()));
        }
        catch (DuplicateKeyException duplicate) {
            IntentIdentity existing = jdbc.query("""
                    SELECT outbox_id,message_id,publication_key,business_intent_key,aggregate_type,
                           aggregate_id,contract_ref,contract_version,body_bytes,body_sha256,
                           correlation_id,causation_id
                    FROM outbox_event
                    WHERE tenant_id=? AND publication_key=?
                    """, INTENT_IDENTITY_MAPPER, Uuids.toBytes(tenantId), intent.publicationKey())
                    .stream().findFirst().orElseThrow(() -> duplicate);
            if (!sameIntent(existing, intent)) {
                throw new IllegalStateException("Conflicting Ticket Notification outbox identity", duplicate);
            }
        }
    }

    public List<Candidate> findCandidates(Instant now, Instant leaseCutoff, int limit) {
        Objects.requireNonNull(now); Objects.requireNonNull(leaseCutoff);
        if (limit < 1 || limit > NotificationEventContract.OUTBOX_MAX_CLAIMS) throw new IllegalArgumentException("limit must be 1..50");
        return jdbc.query("""
                SELECT tenant_id,outbox_id,status,attempt_count,locked_by,correlation_id,lock_version
                FROM outbox_event
                WHERE contract_ref=? AND contract_version=? AND aggregate_type='NOTIFICATION_REQUEST'
                  AND attempt_count<=?
                  AND ((status='PENDING' AND next_attempt_at<=?) OR (status='PUBLISHING' AND locked_at<=?))
                ORDER BY CASE WHEN status='PENDING' THEN next_attempt_at ELSE locked_at END,tenant_id,outbox_id
                LIMIT ?
                """, CANDIDATE_MAPPER, BINDING.contractRef(), NotificationEventContract.VERSION,
                NotificationEventContract.OUTBOX_MAX_ATTEMPTS, ts(now), ts(leaseCutoff), limit);
    }

    public Optional<Claimed> claimForSend(Candidate candidate, String token, Instant now, Instant leaseCutoff) {
        requireTransaction(); requireTenant(candidate.tenantId()); requireToken(token);
        int updated;
        if ("PENDING".equals(candidate.status())) {
            updated = jdbc.update("""
                    UPDATE outbox_event SET status='PUBLISHING',attempt_count=attempt_count+1,locked_by=?,locked_at=?,
                        lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                    WHERE tenant_id=? AND outbox_id=? AND status='PENDING' AND attempt_count=? AND attempt_count<?
                      AND lock_version=? AND next_attempt_at<=?
                    """, token, ts(now), ts(now), Uuids.toBytes(candidate.tenantId()), Uuids.toBytes(candidate.outboxId()),
                    candidate.attemptCount(), NotificationEventContract.OUTBOX_MAX_ATTEMPTS, candidate.lockVersion(), ts(now));
        } else if ("PUBLISHING".equals(candidate.status()) && candidate.lockedBy() != null) {
            updated = jdbc.update("""
                    UPDATE outbox_event SET attempt_count=attempt_count+1,locked_by=?,locked_at=?,lock_version=lock_version+1,
                        updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                    WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING' AND attempt_count=? AND attempt_count<?
                      AND locked_by=? AND lock_version=? AND locked_at<=?
                    """, token, ts(now), ts(now), Uuids.toBytes(candidate.tenantId()), Uuids.toBytes(candidate.outboxId()),
                    candidate.attemptCount(), NotificationEventContract.OUTBOX_MAX_ATTEMPTS, candidate.lockedBy(), candidate.lockVersion(), ts(leaseCutoff));
        } else return Optional.empty();
        if (updated != 1) return Optional.empty();
        return findClaimed(candidate.tenantId(), candidate.outboxId(), token, candidate.attemptCount() + 1, candidate.lockVersion() + 1);
    }

    public Optional<Claimed> claimForRecovery(Candidate candidate, String token, Instant now, Instant leaseCutoff) {
        requireTransaction(); requireTenant(candidate.tenantId()); requireToken(token);
        if (candidate.attemptCount() != NotificationEventContract.OUTBOX_MAX_ATTEMPTS) return Optional.empty();
        int updated;
        if ("PENDING".equals(candidate.status())) {
            updated = jdbc.update("""
                    UPDATE outbox_event SET status='PUBLISHING',locked_by=?,locked_at=?,lock_version=lock_version+1,
                        updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                    WHERE tenant_id=? AND outbox_id=? AND status='PENDING' AND attempt_count=? AND lock_version=? AND next_attempt_at<=?
                    """, token, ts(now), ts(now), Uuids.toBytes(candidate.tenantId()), Uuids.toBytes(candidate.outboxId()),
                    NotificationEventContract.OUTBOX_MAX_ATTEMPTS, candidate.lockVersion(), ts(now));
        } else if ("PUBLISHING".equals(candidate.status()) && candidate.lockedBy() != null) {
            updated = jdbc.update("""
                    UPDATE outbox_event SET locked_by=?,locked_at=?,lock_version=lock_version+1,updated_at=?,
                        updated_by_type='SERVICE',updated_by_id=NULL
                    WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING' AND attempt_count=? AND locked_by=?
                      AND lock_version=? AND locked_at<=?
                    """, token, ts(now), ts(now), Uuids.toBytes(candidate.tenantId()), Uuids.toBytes(candidate.outboxId()),
                    NotificationEventContract.OUTBOX_MAX_ATTEMPTS, candidate.lockedBy(), candidate.lockVersion(), ts(leaseCutoff));
        } else return Optional.empty();
        if (updated != 1) return Optional.empty();
        return findClaimed(candidate.tenantId(), candidate.outboxId(), token,
                NotificationEventContract.OUTBOX_MAX_ATTEMPTS, candidate.lockVersion() + 1);
    }

    public boolean markPublished(Claimed row, Instant now, Instant leaseCutoff) {
        requireTransaction(); requireTenant(row.tenantId());
        int updated = jdbc.update("""
                UPDATE outbox_event SET status='PUBLISHED',locked_by=NULL,locked_at=NULL,last_error_code=NULL,
                    broker_confirmed_at=?,lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING' AND attempt_count=? AND locked_by=?
                  AND lock_version=? AND locked_at>?
                """, ts(now), ts(now), Uuids.toBytes(row.tenantId()), Uuids.toBytes(row.outboxId()), row.attemptCount(), row.claimToken(), row.lockVersion(), ts(leaseCutoff));
        if (updated == 1) resolveReplay(row.tenantId(), row.outboxId(), now);
        return updated == 1;
    }

    public boolean markPending(Claimed row, Instant now, Instant leaseCutoff, Instant next, String errorCode) {
        requireTransaction(); requireTenant(row.tenantId());
        if (row.attemptCount() < 1 || row.attemptCount() >= NotificationEventContract.OUTBOX_MAX_ATTEMPTS) throw new IllegalArgumentException("Only attempts 1..7 may return to PENDING");
        return jdbc.update("""
                UPDATE outbox_event SET status='PENDING',next_attempt_at=?,locked_by=NULL,locked_at=NULL,last_error_code=?,
                    broker_confirmed_at=NULL,lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING' AND attempt_count=? AND locked_by=?
                  AND lock_version=? AND locked_at>?
                """, ts(next), errorCode, ts(now), Uuids.toBytes(row.tenantId()), Uuids.toBytes(row.outboxId()), row.attemptCount(), row.claimToken(), row.lockVersion(), ts(leaseCutoff)) == 1;
    }

    public boolean moveToRecovery(Claimed row, Instant now, Instant leaseCutoff, String code, String summary, UUID recoveryId) {
        requireTransaction(); requireTenant(row.tenantId());
        if (row.attemptCount() != NotificationEventContract.OUTBOX_MAX_ATTEMPTS) throw new IllegalArgumentException("Recovery requires attempt 8");
        return moveAndRecord(row, now, leaseCutoff, code, summary, recoveryId);
    }

    /** Deterministic persisted-contract corruption is routed once to Recovery. */
    public boolean rejectContract(Claimed row, Instant now, Instant leaseCutoff, String code, String summary, UUID recoveryId) {
        requireTransaction(); requireTenant(row.tenantId());
        return moveAndRecord(row, now, leaseCutoff, code, summary, recoveryId);
    }

    private boolean moveAndRecord(Claimed row, Instant now, Instant leaseCutoff, String errorCode, String summary, UUID recoveryId) {
        String code = stableCode(errorCode);
        int updated = jdbc.update("""
                UPDATE outbox_event SET status='RECOVERY_REQUIRED',next_attempt_at=?,locked_by=NULL,locked_at=NULL,
                    last_error_code=?,broker_confirmed_at=NULL,lock_version=lock_version+1,updated_at=?,
                    updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING' AND attempt_count=? AND locked_by=?
                  AND lock_version=? AND locked_at>?
                """, ts(now), code, ts(now), Uuids.toBytes(row.tenantId()), Uuids.toBytes(row.outboxId()), row.attemptCount(), row.claimToken(), row.lockVersion(), ts(leaseCutoff));
        if (updated != 1) return false;
        // Recovery identity belongs to the source row and direction. Never let
        // a retry create a fresh UUID for the same exhausted Outbox fact.
        ensureRecovery(row, now, code, clean(summary), recoveryId(row));
        jdbc.update("""
                INSERT INTO audit_record
                  (tenant_id,audit_id,action_code,target_type,target_id,outcome,actor_type,actor_id,reason,error_code,correlation_id,causation_id,occurred_at)
                VALUES (?,?,?,'OUTBOX_EVENT',?,'FAILED','SERVICE',NULL,?,?,?, ?,?)
                """, Uuids.toBytes(row.tenantId()), Uuids.toBytes(UUID.randomUUID()),
                "TICKET_NOTIFICATION_OUTBOX_RECOVERY_REQUIRED", Uuids.toBytes(row.outboxId()), clean(summary), code,
                Uuids.toBytes(row.correlationId()), Uuids.toBytes(row.causationId()), ts(now));
        return true;
    }

    private void ensureRecovery(Claimed row, Instant now, String code, String summary, UUID recoveryId) {
        try {
            jdbc.update("""
                    INSERT INTO message_recovery
                      (tenant_id,recovery_id,direction,source_record_id,business_intent_key,state,attempt_count,next_attempt_at,
                       last_error_code,last_error_summary,dead_lettered_at,recovery_required_at,replay_count,last_replayed_at,
                       last_replayed_by_type,last_replayed_by_id,last_replay_reason,last_replay_correlation_id,resolved_at,
                       lock_version,created_at,updated_at,updated_by_type,updated_by_id)
                    VALUES (?,?,'OUTBOX',?,?,'OPEN',?,NULL,?,?,NULL,?,0,NULL,NULL,NULL,NULL,NULL,NULL,0,?,?,'SERVICE',NULL)
                    """, Uuids.toBytes(row.tenantId()), Uuids.toBytes(recoveryId), Uuids.toBytes(row.outboxId()), row.businessIntentKey(), row.attemptCount(), code, summary, ts(now), ts(now), ts(now));
        } catch (DuplicateKeyException duplicate) {
            Recovery existing = jdbc.query("SELECT business_intent_key,state,attempt_count FROM message_recovery WHERE tenant_id=? AND direction='OUTBOX' AND source_record_id=?",
                    (rs,n) -> new Recovery(rs.getBytes(1), rs.getString(2), rs.getInt(3)), Uuids.toBytes(row.tenantId()), Uuids.toBytes(row.outboxId())).stream().findFirst().orElseThrow(() -> duplicate);
            if (!Arrays.equals(existing.intent(), row.businessIntentKey()) || !"OPEN".equals(existing.state()) || existing.attemptCount() != row.attemptCount()) throw new IllegalStateException("Conflicting Ticket Notification recovery", duplicate);
        }
    }

    private void resolveReplay(UUID tenantId, UUID outboxId, Instant now) {
        jdbc.update("UPDATE message_recovery SET state='RESOLVED',resolved_at=?,next_attempt_at=NULL,lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL WHERE tenant_id=? AND direction='OUTBOX' AND source_record_id=? AND state='REPLAYING'",
                ts(now), ts(now), Uuids.toBytes(tenantId), Uuids.toBytes(outboxId));
    }

    private Optional<Claimed> findClaimed(UUID tenantId, UUID outboxId, String token, int attempts, long version) {
        return jdbc.query("""
                SELECT tenant_id,outbox_id,message_id,publication_key,business_intent_key,aggregate_type,aggregate_id,
                       contract_ref,contract_version,body_bytes,body_sha256,correlation_id,causation_id,attempt_count,
                       locked_by,locked_at,lock_version,created_at
                FROM outbox_event WHERE tenant_id=? AND outbox_id=? AND status='PUBLISHING' AND attempt_count=? AND locked_by=? AND lock_version=?
                """, CLAIMED_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(outboxId), attempts, token, version).stream().findFirst();
    }

    /** Stable Recovery primary key for one Ticket Notification Outbox source. */
    public static UUID recoveryId(Claimed row) {
        Objects.requireNonNull(row, "row");
        return NotificationWireCodec.uuidFromKey(NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-RECOVERY-V1", row.tenantId(), row.outboxId(), "OUTBOX"));
    }

    private static String stableCode(String value) { return value != null && value.matches("[A-Z][A-Z0-9_]{0,95}") ? value : "BROKER_RESULT_UNKNOWN"; }
    private static String clean(String value) { if (value == null) return null; String v = value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim(); return v.isEmpty() ? null : v.substring(0, Math.min(1000, v.length())); }
    private static void requireTransaction() { if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Ticket Notification Outbox mutation requires a transaction"); }
    private static void requireTenant(UUID id) { TenantContextHolder.current().requireTenant(id); }
    private static void requireToken(String token) { if (token == null || token.isBlank() || token.length() > 96) throw new IllegalArgumentException("claim token must be 1..96 characters"); }
    private static Timestamp ts(Instant value) { return Timestamp.from(Objects.requireNonNull(value)); }
    private static Instant instant(ResultSet rs, String column) throws SQLException { Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant(); }

    private static final org.springframework.jdbc.core.RowMapper<Candidate> CANDIDATE_MAPPER = (rs,n) -> new Candidate(Uuids.toUuid(rs.getBytes("tenant_id")), Uuids.toUuid(rs.getBytes("outbox_id")), rs.getString("status"), rs.getInt("attempt_count"), rs.getString("locked_by"), Uuids.toUuid(rs.getBytes("correlation_id")), rs.getLong("lock_version"));
    private static final org.springframework.jdbc.core.RowMapper<Claimed> CLAIMED_MAPPER = (rs,n) -> new Claimed(Uuids.toUuid(rs.getBytes("tenant_id")), Uuids.toUuid(rs.getBytes("outbox_id")), Uuids.toUuid(rs.getBytes("message_id")), rs.getBytes("publication_key"), rs.getBytes("business_intent_key"), rs.getString("aggregate_type"), Uuids.toUuid(rs.getBytes("aggregate_id")), rs.getString("contract_ref"), rs.getInt("contract_version"), rs.getBytes("body_bytes"), rs.getBytes("body_sha256"), Uuids.toUuid(rs.getBytes("correlation_id")), Uuids.toUuid(rs.getBytes("causation_id")), rs.getInt("attempt_count"), rs.getString("locked_by"), instant(rs,"locked_at"), rs.getLong("lock_version"), instant(rs,"created_at"));
    private static final org.springframework.jdbc.core.RowMapper<IntentIdentity> INTENT_IDENTITY_MAPPER =
            (rs, n) -> new IntentIdentity(Uuids.toUuid(rs.getBytes("outbox_id")),
                    Uuids.toUuid(rs.getBytes("message_id")), rs.getBytes("publication_key"),
                    rs.getBytes("business_intent_key"), rs.getString("aggregate_type"),
                    Uuids.toUuid(rs.getBytes("aggregate_id")), rs.getString("contract_ref"),
                    rs.getInt("contract_version"), rs.getBytes("body_bytes"),
                    rs.getBytes("body_sha256"), Uuids.toUuid(rs.getBytes("correlation_id")),
                    Uuids.toUuid(rs.getBytes("causation_id")));

    private static boolean sameIntent(IntentIdentity row, TicketNotificationIntent.Intent intent) {
        return intent.outboxId().equals(row.outboxId())
                && intent.messageId().equals(row.messageId())
                && Arrays.equals(intent.publicationKey(), row.publicationKey())
                && Arrays.equals(intent.businessIntentKey(), row.businessIntentKey())
                && "NOTIFICATION_REQUEST".equals(row.aggregateType())
                && intent.sourceRefId().equals(row.aggregateId())
                && BINDING.contractRef().equals(row.contractRef())
                && NotificationEventContract.VERSION == row.contractVersion()
                && Arrays.equals(intent.bodyBytes(), row.bodyBytes())
                && Arrays.equals(intent.bodySha256(), row.bodySha256());
    }

    public record Candidate(UUID tenantId, UUID outboxId, String status, int attemptCount, String lockedBy, UUID correlationId, long lockVersion) { }
    public record Claimed(UUID tenantId, UUID outboxId, UUID messageId, byte[] publicationKey, byte[] businessIntentKey, String aggregateType, UUID aggregateId, String contractRef, int contractVersion, byte[] bodyBytes, byte[] bodySha256, UUID correlationId, UUID causationId, int attemptCount, String claimToken, Instant claimedAt, long lockVersion, Instant createdAt) {
        public Claimed { publicationKey=publicationKey.clone(); businessIntentKey=businessIntentKey.clone(); bodyBytes=bodyBytes.clone(); bodySha256=bodySha256.clone(); }
        @Override public byte[] publicationKey(){return publicationKey.clone();} @Override public byte[] businessIntentKey(){return businessIntentKey.clone();} @Override public byte[] bodyBytes(){return bodyBytes.clone();} @Override public byte[] bodySha256(){return bodySha256.clone();}
    }
    private record Recovery(byte[] intent, String state, int attemptCount) { }
    private record IntentIdentity(UUID outboxId, UUID messageId, byte[] publicationKey,
                                  byte[] businessIntentKey, String aggregateType, UUID aggregateId,
                                  String contractRef, int contractVersion, byte[] bodyBytes,
                                  byte[] bodySha256, UUID correlationId, UUID causationId) { }
}
