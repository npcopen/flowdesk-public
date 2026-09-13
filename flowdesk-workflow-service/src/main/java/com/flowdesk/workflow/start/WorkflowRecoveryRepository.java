package com.flowdesk.workflow.start;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

/** Tenant-first persistence for Workflow INBOX recovery and controlled replay. */
final class WorkflowRecoveryRepository {

    private final JdbcTemplate jdbc;

    WorkflowRecoveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<RecoveryFact> findRecovery(UUID tenantId, UUID recoveryId, boolean lock) {
        List<RecoveryFact> rows = jdbc.query("""
                SELECT recovery_id, direction, source_record_id, business_intent_key,
                       state, attempt_count, next_attempt_at, last_error_code,
                       last_error_summary, dead_lettered_at, recovery_required_at,
                       replay_count, last_replayed_at, last_replayed_by_type,
                       last_replayed_by_id, last_replay_reason,
                       last_replay_correlation_id, resolved_at, lock_version,
                       created_at, updated_at
                FROM message_recovery
                WHERE tenant_id = ? AND recovery_id = ?
                """ + (lock ? " FOR UPDATE" : ""),
                WorkflowRecoveryRepository::mapRecovery,
                uuid(tenantId), uuid(recoveryId));
        return rows.stream().findFirst();
    }

    Optional<ReplaySource> findSource(UUID tenantId, UUID inboxId, boolean lock) {
        List<ReplaySource> rows = jdbc.query("""
                SELECT tenant_id, inbox_id, producer_service, message_id, business_intent_key,
                       contract_ref, contract_version, body_bytes, body_sha256,
                       correlation_id, causation_id, occurred_at, status, lock_version
                FROM inbox_message
                WHERE tenant_id = ? AND inbox_id = ?
                """ + (lock ? " FOR UPDATE" : ""),
                WorkflowRecoveryRepository::mapSource,
                uuid(tenantId), uuid(inboxId));
        return rows.stream().findFirst();
    }

    Optional<OutboxReplaySource> findOutboxSource(UUID tenantId, UUID outboxId, boolean lock) {
        return jdbc.query("""
                SELECT outbox_id,contract_ref,contract_version,correlation_id,status,lock_version
                FROM outbox_event
                WHERE tenant_id=? AND outbox_id=?
                """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> new OutboxReplaySource(
                        uuid(rs.getBytes("outbox_id")), rs.getString("contract_ref"),
                        rs.getInt("contract_version"), uuid(rs.getBytes("correlation_id")),
                        rs.getString("status"), rs.getLong("lock_version")),
                uuid(tenantId), uuid(outboxId)).stream().findFirst();
    }

    List<RecoveryFact> findRecoveries(UUID tenantId, String direction, String state,
                                      Boolean deadLettered, String lastErrorCode,
                                      Instant from, Instant to, boolean ascending,
                                      boolean deadLetterSort, Instant cursorTime,
                                      UUID cursorId, int limit) {
        String timeColumn = deadLetterSort ? "dead_lettered_at" : "recovery_required_at";
        StringBuilder sql = new StringBuilder("""
                SELECT recovery_id,direction,source_record_id,business_intent_key,state,
                       attempt_count,next_attempt_at,last_error_code,last_error_summary,
                       dead_lettered_at,recovery_required_at,replay_count,last_replayed_at,
                       last_replayed_by_type,last_replayed_by_id,last_replay_reason,
                       last_replay_correlation_id,resolved_at,lock_version,created_at,updated_at
                FROM message_recovery WHERE tenant_id=?
                """);
        List<Object> args = new ArrayList<>();
        args.add(uuid(tenantId));
        if (direction != null) { sql.append(" AND direction=?"); args.add(direction); }
        if (state != null) { sql.append(" AND state=?"); args.add(state); }
        if (deadLettered != null) {
            sql.append(deadLettered ? " AND dead_lettered_at IS NOT NULL"
                    : " AND dead_lettered_at IS NULL");
        }
        if (lastErrorCode != null) { sql.append(" AND last_error_code=?"); args.add(lastErrorCode); }
        if (from != null) { sql.append(" AND recovery_required_at>=?"); args.add(Timestamp.from(from)); }
        if (to != null) { sql.append(" AND recovery_required_at<=?"); args.add(Timestamp.from(to)); }
        if (deadLetterSort) sql.append(" AND dead_lettered_at IS NOT NULL");
        if (cursorTime != null && cursorId != null) {
            String cmp = ascending ? ">" : "<";
            sql.append(" AND (").append(timeColumn).append(' ').append(cmp)
                    .append(" ? OR (").append(timeColumn).append("=? AND recovery_id ")
                    .append(cmp).append(" ?))");
            args.add(Timestamp.from(cursorTime)); args.add(Timestamp.from(cursorTime));
            args.add(uuid(cursorId));
        }
        sql.append(" ORDER BY ").append(timeColumn).append(ascending ? " ASC" : " DESC")
                .append(",recovery_id").append(ascending ? " ASC" : " DESC")
                .append(" LIMIT ").append(limit);
        return jdbc.query(sql.toString(), WorkflowRecoveryRepository::mapRecovery, args.toArray());
    }

    Optional<AuditAnchor> findAudit(UUID tenantId, UUID auditId) {
        List<AuditAnchor> rows = jdbc.query("""
                SELECT audit_id, action_code, target_type, target_id, outcome,
                       actor_type, actor_id, reason, error_code, correlation_id,
                       causation_id
                FROM audit_record
                WHERE tenant_id = ? AND audit_id = ?
                """, WorkflowRecoveryRepository::mapAudit,
                uuid(tenantId), uuid(auditId));
        return rows.stream().findFirst();
    }

    int markReplaying(UUID tenantId,
                      RecoveryFact recovery,
                      UUID actorId,
                      String reason,
                      UUID attemptCorrelationId,
                      Instant now) {
        return jdbc.update("""
                UPDATE message_recovery
                SET state = 'REPLAYING', next_attempt_at = NULL,
                    replay_count = replay_count + 1, last_replayed_at = ?,
                    last_replayed_by_type = 'USER', last_replayed_by_id = ?,
                    last_replay_reason = ?, last_replay_correlation_id = ?,
                    lock_version = lock_version + 1, updated_at = ?,
                    updated_by_type = 'USER', updated_by_id = ?
                WHERE tenant_id = ? AND recovery_id = ? AND direction = 'INBOX'
                  AND source_record_id = ? AND state = 'OPEN' AND lock_version = ?
                """, Timestamp.from(now), uuid(actorId), reason,
                uuid(attemptCorrelationId), Timestamp.from(now), uuid(actorId),
                uuid(tenantId), uuid(recovery.recoveryId()),
                uuid(recovery.sourceRecordId()), recovery.lockVersion());
    }

    int markOutboxReplayingAndQueue(UUID tenantId, RecoveryFact recovery,
                                    OutboxReplaySource source, UUID actorId, String reason,
                                    UUID attemptCorrelationId, Instant now) {
        int recoveryChanged = jdbc.update("""
                UPDATE message_recovery
                SET state='REPLAYING',next_attempt_at=NULL,replay_count=replay_count+1,
                    last_replayed_at=?,last_replayed_by_type='USER',last_replayed_by_id=?,
                    last_replay_reason=?,last_replay_correlation_id=?,
                    lock_version=lock_version+1,updated_at=?,updated_by_type='USER',updated_by_id=?
                WHERE tenant_id=? AND recovery_id=? AND direction='OUTBOX'
                  AND source_record_id=? AND state='OPEN' AND lock_version=?
                """, Timestamp.from(now), uuid(actorId), reason, uuid(attemptCorrelationId),
                Timestamp.from(now), uuid(actorId), uuid(tenantId), uuid(recovery.recoveryId()),
                uuid(source.outboxId()), recovery.lockVersion());
        if (recoveryChanged != 1) return recoveryChanged;
        int outboxChanged = jdbc.update("""
                UPDATE outbox_event
                SET status='PENDING',attempt_count=0,next_attempt_at=?,locked_by=NULL,
                    locked_at=NULL,last_error_code=NULL,broker_confirmed_at=NULL,
                    lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND outbox_id=? AND status='RECOVERY_REQUIRED' AND lock_version=?
                """, Timestamp.from(now), Timestamp.from(now), uuid(tenantId),
                uuid(source.outboxId()), source.lockVersion());
        if (outboxChanged != 1) {
            throw new IllegalStateException("Workflow Outbox replay source CAS was lost");
        }
        return 1;
    }

    void insertReplayRequestAudit(UUID tenantId,
                                  UUID auditId,
                                  UUID recoveryId,
                                  UUID actorId,
                                  String requestDigest,
                                  UUID attemptCorrelationId,
                                  UUID sourceCorrelationId,
                                  Instant now) {
        insertAudit(tenantId, auditId, "WORKFLOW_RECOVERY_REPLAY_REQUESTED",
                recoveryId, "SUCCEEDED", actorId, requestDigest, null,
                attemptCorrelationId, sourceCorrelationId, now);
    }

    int reopenFailedReplay(UUID tenantId,
                           UUID recoveryId,
                           UUID attemptCorrelationId,
                           String errorCode,
                           String errorSummary,
                           Instant now) {
        return jdbc.update("""
                UPDATE message_recovery
                SET state = 'OPEN', next_attempt_at = NULL, last_error_code = ?,
                    last_error_summary = ?, lock_version = lock_version + 1,
                    updated_at = ?, updated_by_type = 'SERVICE', updated_by_id = NULL
                WHERE tenant_id = ? AND recovery_id = ? AND direction = 'INBOX'
                  AND state = 'REPLAYING' AND last_replay_correlation_id = ?
                """, errorCode, errorSummary, Timestamp.from(now), uuid(tenantId),
                uuid(recoveryId), uuid(attemptCorrelationId));
    }

    void insertReplayOutcomeAudit(UUID tenantId,
                                  UUID auditId,
                                  UUID recoveryId,
                                  UUID actorId,
                                  String outcome,
                                  String reason,
                                  String errorCode,
                                  UUID attemptCorrelationId,
                                  UUID sourceCorrelationId,
                                  Instant now) {
        if (findAudit(tenantId, auditId).isPresent()) {
            return;
        }
        insertAudit(tenantId, auditId, "WORKFLOW_RECOVERY_REPLAY_COMPLETED",
                recoveryId, outcome, actorId, reason, errorCode,
                attemptCorrelationId, sourceCorrelationId, now);
    }

    private void insertAudit(UUID tenantId,
                             UUID auditId,
                             String action,
                             UUID recoveryId,
                             String outcome,
                             UUID actorId,
                             String reason,
                             String errorCode,
                             UUID correlationId,
                             UUID causationId,
                             Instant now) {
        jdbc.update("""
                INSERT INTO audit_record
                  (tenant_id, audit_id, action_code, target_type, target_id, outcome,
                   actor_type, actor_id, reason, error_code, correlation_id,
                   causation_id, occurred_at)
                VALUES (?, ?, ?, 'MESSAGE_RECOVERY', ?, ?, 'USER', ?, ?, ?, ?, ?, ?)
                """, uuid(tenantId), uuid(auditId), action, uuid(recoveryId), outcome,
                uuid(actorId), reason, errorCode, uuid(correlationId),
                nullableUuid(causationId), Timestamp.from(now));
    }

    static WorkflowRecoveryView toView(RecoveryFact fact) {
        WorkflowRecoveryView.ReplayedBy replayedBy =
                fact.lastReplayedById() == null ? null
                        : new WorkflowRecoveryView.ReplayedBy(
                                fact.lastReplayedByType(), fact.lastReplayedById());
        return new WorkflowRecoveryView(
                fact.recoveryId(), "WORKFLOW", fact.direction(), fact.sourceRecordId(),
                HexFormat.of().formatHex(fact.businessIntentKey()), fact.state(),
                fact.attemptCount(), fact.nextAttemptAt(), fact.lastErrorCode(),
                fact.lastErrorSummary(), fact.deadLetteredAt(), fact.recoveryRequiredAt(),
                fact.replayCount(), fact.lastReplayedAt(), replayedBy,
                fact.lastReplayReason(), fact.resolvedAt(), fact.lockVersion(),
                fact.createdAt(), fact.updatedAt());
    }

    private static RecoveryFact mapRecovery(ResultSet rs, int row) throws SQLException {
        return new RecoveryFact(
                uuid(rs.getBytes("recovery_id")),
                rs.getString("direction"),
                uuid(rs.getBytes("source_record_id")),
                rs.getBytes("business_intent_key"),
                rs.getString("state"),
                rs.getInt("attempt_count"),
                instant(rs, "next_attempt_at"),
                rs.getString("last_error_code"),
                rs.getString("last_error_summary"),
                instant(rs, "dead_lettered_at"),
                instant(rs, "recovery_required_at"),
                rs.getInt("replay_count"),
                instant(rs, "last_replayed_at"),
                rs.getString("last_replayed_by_type"),
                nullableUuid(rs.getBytes("last_replayed_by_id")),
                rs.getString("last_replay_reason"),
                nullableUuid(rs.getBytes("last_replay_correlation_id")),
                instant(rs, "resolved_at"),
                rs.getLong("lock_version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static ReplaySource mapSource(ResultSet rs, int row) throws SQLException {
        return new ReplaySource(
                uuid(rs.getBytes("tenant_id")),
                uuid(rs.getBytes("inbox_id")),
                rs.getString("producer_service"),
                uuid(rs.getBytes("message_id")),
                rs.getBytes("business_intent_key"),
                rs.getString("contract_ref"),
                rs.getInt("contract_version"),
                rs.getBytes("body_bytes"),
                rs.getBytes("body_sha256"),
                uuid(rs.getBytes("correlation_id")),
                nullableUuid(rs.getBytes("causation_id")),
                instant(rs, "occurred_at"),
                rs.getString("status"),
                rs.getLong("lock_version"));
    }

    private static AuditAnchor mapAudit(ResultSet rs, int row) throws SQLException {
        return new AuditAnchor(
                uuid(rs.getBytes("audit_id")), rs.getString("action_code"),
                rs.getString("target_type"), nullableUuid(rs.getBytes("target_id")),
                rs.getString("outcome"), rs.getString("actor_type"),
                nullableUuid(rs.getBytes("actor_id")), rs.getString("reason"),
                rs.getString("error_code"), uuid(rs.getBytes("correlation_id")),
                nullableUuid(rs.getBytes("causation_id")));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static byte[] nullableUuid(UUID value) {
        return value == null ? null : uuid(value);
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static UUID nullableUuid(byte[] value) {
        return value == null ? null : uuid(value);
    }

    record RecoveryFact(UUID recoveryId,
                        String direction,
                        UUID sourceRecordId,
                        byte[] businessIntentKey,
                        String state,
                        int attemptCount,
                        Instant nextAttemptAt,
                        String lastErrorCode,
                        String lastErrorSummary,
                        Instant deadLetteredAt,
                        Instant recoveryRequiredAt,
                        int replayCount,
                        Instant lastReplayedAt,
                        String lastReplayedByType,
                        UUID lastReplayedById,
                        String lastReplayReason,
                        UUID lastReplayCorrelationId,
                        Instant resolvedAt,
                        long lockVersion,
                        Instant createdAt,
                        Instant updatedAt) {

        RecoveryFact {
            businessIntentKey = businessIntentKey.clone();
        }

        @Override
        public byte[] businessIntentKey() {
            return businessIntentKey.clone();
        }
    }

    record ReplaySource(UUID tenantId,
                        UUID inboxId,
                        String producerService,
                        UUID messageId,
                        byte[] businessIntentKey,
                        String contractRef,
                        int contractVersion,
                        byte[] bodyBytes,
                        byte[] bodySha256,
                        UUID correlationId,
                        UUID causationId,
                        Instant occurredAt,
                        String status,
                        long lockVersion) {

        ReplaySource {
            businessIntentKey = businessIntentKey.clone();
            bodyBytes = bodyBytes.clone();
            bodySha256 = bodySha256.clone();
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

    record OutboxReplaySource(UUID outboxId, String contractRef, int contractVersion,
                              UUID correlationId, String status, long lockVersion) {
    }

    record AuditAnchor(UUID auditId,
                       String actionCode,
                       String targetType,
                       UUID targetId,
                       String outcome,
                       String actorType,
                       UUID actorId,
                       String reason,
                       String errorCode,
                       UUID correlationId,
                       UUID causationId) {
    }
}
