package com.flowdesk.ticket.outbox;

import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.ticket.event.TicketEventContract;
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

/** Persistence boundary for the frozen Ticket workflow-start Outbox state machine. */
@Repository
public class TicketOutboxRepository {

    private static final String CONTRACT_REF =
            TicketEventContract.WorkflowStartRequested.CONTRACT_REF;
    private static final int CONTRACT_VERSION =
            TicketEventContract.WorkflowStartRequested.EVENT_VERSION;
    private static final String AGGREGATE_TYPE =
            TicketEventContract.WorkflowStartRequested.AGGREGATE_TYPE;
    private static final int MAX_AUTOMATIC_SENDS =
            TicketEventContract.WorkflowStartRequested.MAX_AUTOMATIC_SENDS;

    private final JdbcTemplate jdbc;

    public TicketOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Bounded cross-tenant discovery. It intentionally returns only persisted task references;
     * every full read and mutation below is tenant-first and runs inside a restored task context.
     */
    public List<Candidate> findCandidates(Instant now, Instant leaseCutoff, int limit) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseCutoff, "leaseCutoff");
        if (limit < 1 || limit > TicketEventContract.WorkflowStartRequested.MAX_CLAIMS_PER_RUN) {
            throw new IllegalArgumentException("limit must be between 1 and 50");
        }
        return jdbc.query("""
                SELECT tenant_id, outbox_id, status, attempt_count, locked_by,
                       correlation_id, lock_version
                FROM outbox_event
                WHERE contract_ref = ?
                  AND contract_version = ?
                  AND aggregate_type = ?
                  AND attempt_count <= ?
                  AND (
                       (status = 'PENDING' AND next_attempt_at <= ?)
                       OR
                       (status = 'PUBLISHING' AND locked_at <= ?)
                  )
                ORDER BY CASE WHEN status = 'PENDING' THEN next_attempt_at ELSE locked_at END,
                         tenant_id, outbox_id
                LIMIT ?
                """, CANDIDATE_MAPPER, CONTRACT_REF, CONTRACT_VERSION, AGGREGATE_TYPE,
                MAX_AUTOMATIC_SENDS, Timestamp.from(now), Timestamp.from(leaseCutoff), limit);
    }

    /** Claims one due row immediately before a Broker send and consumes one automatic attempt. */
    public Optional<ClaimedOutbox> claimForSend(
            Candidate candidate, String claimToken, Instant claimedAt, Instant leaseCutoff) {
        requireTransaction("claimForSend");
        requireClaimToken(claimToken);
        Objects.requireNonNull(candidate, "candidate");
        requireTenant(candidate.tenantId());
        int updated;
        if ("PENDING".equals(candidate.status())) {
            updated = jdbc.update("""
                    UPDATE outbox_event
                    SET status = 'PUBLISHING',
                        attempt_count = attempt_count + 1,
                        locked_by = ?,
                        locked_at = ?,
                        lock_version = lock_version + 1,
                        updated_at = ?,
                        updated_by_type = 'SERVICE',
                        updated_by_id = NULL
                    WHERE tenant_id = ? AND outbox_id = ?
                      AND status = 'PENDING'
                      AND attempt_count = ? AND attempt_count < ?
                      AND lock_version = ?
                      AND next_attempt_at <= ?
                    """, claimToken, timestamp(claimedAt), timestamp(claimedAt),
                    Uuids.toBytes(candidate.tenantId()), Uuids.toBytes(candidate.outboxId()),
                    candidate.attemptCount(), MAX_AUTOMATIC_SENDS, candidate.lockVersion(),
                    timestamp(claimedAt));
        }
        else if ("PUBLISHING".equals(candidate.status()) && candidate.lockedBy() != null) {
            updated = jdbc.update("""
                    UPDATE outbox_event
                    SET attempt_count = attempt_count + 1,
                        locked_by = ?,
                        locked_at = ?,
                        lock_version = lock_version + 1,
                        updated_at = ?,
                        updated_by_type = 'SERVICE',
                        updated_by_id = NULL
                    WHERE tenant_id = ? AND outbox_id = ?
                      AND status = 'PUBLISHING'
                      AND attempt_count = ? AND attempt_count < ?
                      AND locked_by = ?
                      AND lock_version = ?
                      AND locked_at <= ?
                    """, claimToken, timestamp(claimedAt), timestamp(claimedAt),
                    Uuids.toBytes(candidate.tenantId()), Uuids.toBytes(candidate.outboxId()),
                    candidate.attemptCount(), MAX_AUTOMATIC_SENDS, candidate.lockedBy(),
                    candidate.lockVersion(), timestamp(leaseCutoff));
        }
        else {
            return Optional.empty();
        }
        if (updated != 1) {
            return Optional.empty();
        }
        return Optional.of(findClaimed(candidate.tenantId(), candidate.outboxId(), claimToken,
                candidate.attemptCount() + 1, candidate.lockVersion() + 1)
                .orElseThrow(() -> new IllegalStateException(
                        "Claimed Outbox row could not be reread in its claim transaction")));
    }

    /**
     * Takes over an exhausted row only to converge recovery. It neither increments the eighth
     * consumed attempt nor permits a ninth Broker send.
     */
    public Optional<ClaimedOutbox> claimForRecovery(
            Candidate candidate, String claimToken, Instant claimedAt, Instant leaseCutoff) {
        requireTransaction("claimForRecovery");
        requireClaimToken(claimToken);
        Objects.requireNonNull(candidate, "candidate");
        requireTenant(candidate.tenantId());
        if (candidate.attemptCount() != MAX_AUTOMATIC_SENDS) {
            return Optional.empty();
        }
        int updated;
        if ("PENDING".equals(candidate.status())) {
            updated = jdbc.update("""
                    UPDATE outbox_event
                    SET status = 'PUBLISHING',
                        locked_by = ?,
                        locked_at = ?,
                        lock_version = lock_version + 1,
                        updated_at = ?,
                        updated_by_type = 'SERVICE',
                        updated_by_id = NULL
                    WHERE tenant_id = ? AND outbox_id = ?
                      AND status = 'PENDING'
                      AND attempt_count = ?
                      AND lock_version = ?
                      AND next_attempt_at <= ?
                    """, claimToken, timestamp(claimedAt), timestamp(claimedAt),
                    Uuids.toBytes(candidate.tenantId()), Uuids.toBytes(candidate.outboxId()),
                    MAX_AUTOMATIC_SENDS, candidate.lockVersion(), timestamp(claimedAt));
        }
        else if ("PUBLISHING".equals(candidate.status()) && candidate.lockedBy() != null) {
            updated = jdbc.update("""
                    UPDATE outbox_event
                    SET locked_by = ?,
                        locked_at = ?,
                        lock_version = lock_version + 1,
                        updated_at = ?,
                        updated_by_type = 'SERVICE',
                        updated_by_id = NULL
                    WHERE tenant_id = ? AND outbox_id = ?
                      AND status = 'PUBLISHING'
                      AND attempt_count = ?
                      AND locked_by = ?
                      AND lock_version = ?
                      AND locked_at <= ?
                    """, claimToken, timestamp(claimedAt), timestamp(claimedAt),
                    Uuids.toBytes(candidate.tenantId()), Uuids.toBytes(candidate.outboxId()),
                    MAX_AUTOMATIC_SENDS, candidate.lockedBy(), candidate.lockVersion(),
                    timestamp(leaseCutoff));
        }
        else {
            return Optional.empty();
        }
        if (updated != 1) {
            return Optional.empty();
        }
        return Optional.of(findClaimed(candidate.tenantId(), candidate.outboxId(), claimToken,
                MAX_AUTOMATIC_SENDS, candidate.lockVersion() + 1)
                .orElseThrow(() -> new IllegalStateException(
                        "Recovery-only claim could not be reread in its claim transaction")));
    }

    public boolean markPublished(ClaimedOutbox claimed, Instant confirmedAt, Instant leaseCutoff) {
        requireTransaction("markPublished");
        requireTenant(claimed.tenantId());
        int updated = jdbc.update("""
                UPDATE outbox_event
                SET status = 'PUBLISHED',
                    locked_by = NULL,
                    locked_at = NULL,
                    last_error_code = NULL,
                    broker_confirmed_at = ?,
                    lock_version = lock_version + 1,
                    updated_at = ?,
                    updated_by_type = 'SERVICE',
                    updated_by_id = NULL
                WHERE tenant_id = ? AND outbox_id = ?
                  AND status = 'PUBLISHING'
                  AND attempt_count = ?
                  AND locked_by = ?
                  AND lock_version = ?
                  AND locked_at > ?
                """, timestamp(confirmedAt), timestamp(confirmedAt),
                Uuids.toBytes(claimed.tenantId()), Uuids.toBytes(claimed.outboxId()),
                claimed.attemptCount(), claimed.claimToken(), claimed.lockVersion(),
                timestamp(leaseCutoff));
        if (updated == 1) {
            jdbc.update("""
                    UPDATE message_recovery
                    SET state='RESOLVED',resolved_at=?,next_attempt_at=NULL,
                        lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',
                        updated_by_id=NULL
                    WHERE tenant_id=? AND direction='OUTBOX' AND source_record_id=?
                      AND state='REPLAYING'
                    """, timestamp(confirmedAt), timestamp(confirmedAt),
                    Uuids.toBytes(claimed.tenantId()), Uuids.toBytes(claimed.outboxId()));
        }
        return updated == 1;
    }

    public boolean markPending(ClaimedOutbox claimed, Instant completedAt, Instant leaseCutoff,
                               Instant nextAttemptAt, String errorCode) {
        requireTransaction("markPending");
        requireTenant(claimed.tenantId());
        if (claimed.attemptCount() < 1 || claimed.attemptCount() >= MAX_AUTOMATIC_SENDS) {
            throw new IllegalArgumentException("Only attempts 1 through 7 may return to PENDING");
        }
        return jdbc.update("""
                UPDATE outbox_event
                SET status = 'PENDING',
                    next_attempt_at = ?,
                    locked_by = NULL,
                    locked_at = NULL,
                    last_error_code = ?,
                    broker_confirmed_at = NULL,
                    lock_version = lock_version + 1,
                    updated_at = ?,
                    updated_by_type = 'SERVICE',
                    updated_by_id = NULL
                WHERE tenant_id = ? AND outbox_id = ?
                  AND status = 'PUBLISHING'
                  AND attempt_count = ?
                  AND locked_by = ?
                  AND lock_version = ?
                  AND locked_at > ?
                """, timestamp(nextAttemptAt), errorCode, timestamp(completedAt),
                Uuids.toBytes(claimed.tenantId()), Uuids.toBytes(claimed.outboxId()),
                claimed.attemptCount(), claimed.claimToken(), claimed.lockVersion(),
                timestamp(leaseCutoff)) == 1;
    }

    /** Completes the eighth-attempt recovery boundary across all three Ticket-owned tables. */
    public boolean moveToRecovery(ClaimedOutbox claimed, Instant completedAt, Instant leaseCutoff,
                                  String errorCode, String safeErrorSummary, UUID recoveryId) {
        requireTransaction("moveToRecovery");
        requireTenant(claimed.tenantId());
        if (claimed.attemptCount() != MAX_AUTOMATIC_SENDS) {
            throw new IllegalArgumentException("Recovery requires the eighth consumed attempt");
        }
        int outboxUpdated = jdbc.update("""
                UPDATE outbox_event
                SET status = 'RECOVERY_REQUIRED',
                    next_attempt_at = ?,
                    locked_by = NULL,
                    locked_at = NULL,
                    last_error_code = ?,
                    broker_confirmed_at = NULL,
                    lock_version = lock_version + 1,
                    updated_at = ?,
                    updated_by_type = 'SERVICE',
                    updated_by_id = NULL
                WHERE tenant_id = ? AND outbox_id = ?
                  AND status = 'PUBLISHING'
                  AND attempt_count = ?
                  AND locked_by = ?
                  AND lock_version = ?
                  AND locked_at > ?
                """, timestamp(completedAt), errorCode, timestamp(completedAt),
                Uuids.toBytes(claimed.tenantId()), Uuids.toBytes(claimed.outboxId()),
                MAX_AUTOMATIC_SENDS, claimed.claimToken(), claimed.lockVersion(),
                timestamp(leaseCutoff));
        if (outboxUpdated == 0) {
            return false;
        }
        if (outboxUpdated != 1) {
            throw new IllegalStateException("Outbox recovery CAS updated an unexpected row count");
        }

        LinkState link = jdbc.query("""
                SELECT status, lock_version
                FROM workflow_start_link
                WHERE tenant_id = ? AND ticket_id = ?
                """, (rs, rowNum) -> new LinkState(rs.getString("status"),
                        rs.getLong("lock_version")), Uuids.toBytes(claimed.tenantId()),
                Uuids.toBytes(claimed.aggregateId())).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "WorkflowStartLink is missing for exhausted Outbox"));
        if (!"REQUESTED".equals(link.status())) {
            throw new IllegalStateException("WorkflowStartLink is not REQUESTED at Outbox exhaustion");
        }
        int linkUpdated = jdbc.update("""
                UPDATE workflow_start_link
                SET status = 'RECOVERY_REQUIRED',
                    recovery_required_at = ?,
                    recovery_reason_code = ?,
                    retry_count = ?,
                    last_attempt_at = ?,
                    lock_version = lock_version + 1,
                    updated_at = ?,
                    updated_by_type = 'SERVICE',
                    updated_by_id = NULL
                WHERE tenant_id = ? AND ticket_id = ?
                  AND status = 'REQUESTED'
                  AND lock_version = ?
                """, timestamp(completedAt), errorCode, MAX_AUTOMATIC_SENDS,
                timestamp(completedAt), timestamp(completedAt),
                Uuids.toBytes(claimed.tenantId()), Uuids.toBytes(claimed.aggregateId()),
                link.lockVersion());
        if (linkUpdated != 1) {
            throw new IllegalStateException("WorkflowStartLink recovery CAS lost its version");
        }

        ensureRecovery(claimed, completedAt, errorCode, safeErrorSummary, recoveryId);
        return true;
    }

    private Optional<ClaimedOutbox> findClaimed(UUID tenantId, UUID outboxId, String claimToken,
                                                int attemptCount, long lockVersion) {
        return jdbc.query("""
                SELECT tenant_id, outbox_id, message_id, publication_key, business_intent_key,
                       aggregate_type, aggregate_id, contract_ref, contract_version, body_bytes,
                       body_sha256, correlation_id, causation_id, attempt_count, locked_by,
                       locked_at, lock_version, created_at
                FROM outbox_event
                WHERE tenant_id = ? AND outbox_id = ?
                  AND status = 'PUBLISHING'
                  AND attempt_count = ?
                  AND locked_by = ?
                  AND lock_version = ?
                """, CLAIMED_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(outboxId),
                attemptCount, claimToken, lockVersion).stream().findFirst();
    }

    private void ensureRecovery(ClaimedOutbox claimed, Instant completedAt, String errorCode,
                                String safeErrorSummary, UUID recoveryId) {
        Optional<RecoveryState> existing = findRecovery(claimed.tenantId(), claimed.outboxId());
        if (existing.isPresent()) {
            validateRecovery(existing.get(), claimed);
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO message_recovery
                      (tenant_id, recovery_id, direction, source_record_id, business_intent_key,
                       state, attempt_count, next_attempt_at, last_error_code, last_error_summary,
                       dead_lettered_at, recovery_required_at, replay_count, last_replayed_at,
                       last_replayed_by_type, last_replayed_by_id, last_replay_reason, resolved_at,
                       lock_version, created_at, updated_at, updated_by_type, updated_by_id)
                    VALUES (?, ?, 'OUTBOX', ?, ?, 'OPEN', ?, NULL, ?, ?, NULL, ?,
                            0, NULL, NULL, NULL, NULL, NULL, 0, ?, ?, 'SERVICE', NULL)
                    """, Uuids.toBytes(claimed.tenantId()), Uuids.toBytes(recoveryId),
                    Uuids.toBytes(claimed.outboxId()), claimed.businessIntentKey(),
                    MAX_AUTOMATIC_SENDS, errorCode, safeErrorSummary, timestamp(completedAt),
                    timestamp(completedAt), timestamp(completedAt));
        }
        catch (DuplicateKeyException duplicate) {
            RecoveryState concurrent = findRecovery(claimed.tenantId(), claimed.outboxId())
                    .orElseThrow(() -> duplicate);
            validateRecovery(concurrent, claimed);
        }
    }

    private Optional<RecoveryState> findRecovery(UUID tenantId, UUID outboxId) {
        return jdbc.query("""
                SELECT business_intent_key, state, attempt_count, dead_lettered_at
                FROM message_recovery
                WHERE tenant_id = ? AND direction = 'OUTBOX' AND source_record_id = ?
                """, (rs, rowNum) -> new RecoveryState(rs.getBytes("business_intent_key"),
                        rs.getString("state"), rs.getInt("attempt_count"),
                        instant(rs, "dead_lettered_at")), Uuids.toBytes(tenantId),
                Uuids.toBytes(outboxId)).stream().findFirst();
    }

    private static void validateRecovery(RecoveryState recovery, ClaimedOutbox claimed) {
        if (!Arrays.equals(recovery.businessIntentKey(), claimed.businessIntentKey())
                || !"OPEN".equals(recovery.state())
                || recovery.attemptCount() != MAX_AUTOMATIC_SENDS
                || recovery.deadLetteredAt() != null) {
            throw new IllegalStateException("Existing Outbox recovery fact conflicts with exhaustion");
        }
    }

    private static void requireClaimToken(String claimToken) {
        if (claimToken == null || claimToken.isBlank() || claimToken.length() > 96) {
            throw new IllegalArgumentException("claimToken must be 1..96 characters");
        }
    }

    private static void requireTransaction(String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(operation + " requires an active local transaction");
        }
    }

    private static void requireTenant(UUID tenantId) {
        TenantContextHolder.current().requireTenant(tenantId);
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(Objects.requireNonNull(value, "timestamp value"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static final org.springframework.jdbc.core.RowMapper<Candidate> CANDIDATE_MAPPER =
            (rs, rowNum) -> new Candidate(
                    Uuids.toUuid(rs.getBytes("tenant_id")),
                    Uuids.toUuid(rs.getBytes("outbox_id")),
                    rs.getString("status"),
                    rs.getInt("attempt_count"),
                    rs.getString("locked_by"),
                    Uuids.toUuid(rs.getBytes("correlation_id")),
                    rs.getLong("lock_version"));

    private static final org.springframework.jdbc.core.RowMapper<ClaimedOutbox> CLAIMED_MAPPER =
            (rs, rowNum) -> new ClaimedOutbox(
                    Uuids.toUuid(rs.getBytes("tenant_id")),
                    Uuids.toUuid(rs.getBytes("outbox_id")),
                    Uuids.toUuid(rs.getBytes("message_id")),
                    rs.getBytes("publication_key"),
                    rs.getBytes("business_intent_key"),
                    rs.getString("aggregate_type"),
                    Uuids.toUuid(rs.getBytes("aggregate_id")),
                    rs.getString("contract_ref"),
                    rs.getInt("contract_version"),
                    rs.getBytes("body_bytes"),
                    rs.getBytes("body_sha256"),
                    Uuids.toUuid(rs.getBytes("correlation_id")),
                    Uuids.toUuid(rs.getBytes("causation_id")),
                    rs.getInt("attempt_count"),
                    rs.getString("locked_by"),
                    instant(rs, "locked_at"),
                    rs.getLong("lock_version"),
                    instant(rs, "created_at"));

    public record Candidate(UUID tenantId, UUID outboxId, String status, int attemptCount,
                            String lockedBy, UUID correlationId, long lockVersion) {
    }

    public record ClaimedOutbox(
            UUID tenantId,
            UUID outboxId,
            UUID messageId,
            byte[] publicationKey,
            byte[] businessIntentKey,
            String aggregateType,
            UUID aggregateId,
            String contractRef,
            int contractVersion,
            byte[] bodyBytes,
            byte[] bodySha256,
            UUID correlationId,
            UUID causationId,
            int attemptCount,
            String claimToken,
            Instant claimedAt,
            long lockVersion,
            Instant createdAt) {

        public ClaimedOutbox {
            publicationKey = publicationKey.clone();
            businessIntentKey = businessIntentKey.clone();
            bodyBytes = bodyBytes.clone();
            bodySha256 = bodySha256.clone();
        }

        @Override
        public byte[] publicationKey() {
            return publicationKey.clone();
        }

        @Override
        public byte[] businessIntentKey() {
            return businessIntentKey.clone();
        }

        @Override
        public byte[] bodyBytes() {
            return bodyBytes.clone();
        }

        @Override
        public byte[] bodySha256() {
            return bodySha256.clone();
        }
    }

    private record LinkState(String status, long lockVersion) {
    }

    private record RecoveryState(byte[] businessIntentKey, String state, int attemptCount,
                                 Instant deadLetteredAt) {
    }
}
