package com.flowdesk.ticket.recovery;

import com.flowdesk.ticket.repository.TicketRepository.Uuids;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class TicketRecoveryRepository {
    private final JdbcTemplate jdbc;
    TicketRecoveryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Optional<Recovery> find(UUID tenantId, UUID recoveryId, boolean lock) {
        return jdbc.query("""
                SELECT recovery_id,direction,source_record_id,business_intent_key,state,
                       attempt_count,next_attempt_at,last_error_code,last_error_summary,
                       dead_lettered_at,recovery_required_at,replay_count,last_replayed_at,
                       last_replayed_by_type,last_replayed_by_id,last_replay_reason,
                       last_replay_correlation_id,resolved_at,lock_version,created_at,updated_at
                FROM message_recovery WHERE tenant_id=? AND recovery_id=?
                """ + (lock ? " FOR UPDATE" : ""), RECOVERY_MAPPER,
                Uuids.toBytes(tenantId), Uuids.toBytes(recoveryId)).stream().findFirst();
    }

    List<Recovery> list(UUID tenantId, String direction, String state, Boolean deadLettered,
                        String error, Instant from, Instant to, boolean ascending,
                        boolean deadSort, Instant cursorTime, UUID cursorId, int limit) {
        String column = deadSort ? "dead_lettered_at" : "recovery_required_at";
        StringBuilder sql = new StringBuilder("""
                SELECT recovery_id,direction,source_record_id,business_intent_key,state,
                       attempt_count,next_attempt_at,last_error_code,last_error_summary,
                       dead_lettered_at,recovery_required_at,replay_count,last_replayed_at,
                       last_replayed_by_type,last_replayed_by_id,last_replay_reason,
                       last_replay_correlation_id,resolved_at,lock_version,created_at,updated_at
                FROM message_recovery WHERE tenant_id=?
                """);
        List<Object> args = new ArrayList<>(); args.add(Uuids.toBytes(tenantId));
        if (direction != null) { sql.append(" AND direction=?"); args.add(direction); }
        if (state != null) { sql.append(" AND state=?"); args.add(state); }
        if (deadLettered != null) sql.append(deadLettered
                ? " AND dead_lettered_at IS NOT NULL" : " AND dead_lettered_at IS NULL");
        if (error != null) { sql.append(" AND last_error_code=?"); args.add(error); }
        if (from != null) { sql.append(" AND recovery_required_at>=?"); args.add(Timestamp.from(from)); }
        if (to != null) { sql.append(" AND recovery_required_at<=?"); args.add(Timestamp.from(to)); }
        if (deadSort) sql.append(" AND dead_lettered_at IS NOT NULL");
        if (cursorTime != null) {
            String cmp = ascending ? ">" : "<";
            sql.append(" AND (").append(column).append(' ').append(cmp)
                    .append(" ? OR (").append(column).append("=? AND recovery_id ")
                    .append(cmp).append(" ?))");
            args.add(Timestamp.from(cursorTime)); args.add(Timestamp.from(cursorTime));
            args.add(Uuids.toBytes(cursorId));
        }
        sql.append(" ORDER BY ").append(column).append(ascending ? " ASC" : " DESC")
                .append(",recovery_id").append(ascending ? " ASC" : " DESC")
                .append(" LIMIT ").append(limit);
        return jdbc.query(sql.toString(), RECOVERY_MAPPER, args.toArray());
    }

    Optional<InboxSource> inboxSource(UUID tenantId, UUID inboxId, boolean lock) {
        return jdbc.query("""
                SELECT inbox_id,message_id,business_intent_key,contract_ref,contract_version,
                       body_bytes,body_sha256,correlation_id,causation_id,occurred_at,status,lock_version
                FROM inbox_message WHERE tenant_id=? AND inbox_id=?
                """ + (lock ? " FOR UPDATE" : ""), (rs, n) -> new InboxSource(
                        Uuids.toUuid(rs.getBytes("inbox_id")), Uuids.toUuid(rs.getBytes("message_id")),
                        rs.getBytes("business_intent_key"), rs.getString("contract_ref"),
                        rs.getInt("contract_version"), rs.getBytes("body_bytes"),
                        rs.getBytes("body_sha256"), Uuids.toUuid(rs.getBytes("correlation_id")),
                        Uuids.toUuid(rs.getBytes("causation_id")), instant(rs, "occurred_at"),
                        rs.getString("status"), rs.getLong("lock_version")),
                Uuids.toBytes(tenantId), Uuids.toBytes(inboxId)).stream().findFirst();
    }

    Optional<OutboxSource> outboxSource(UUID tenantId, UUID outboxId, boolean lock) {
        return jdbc.query("""
                SELECT o.outbox_id,o.contract_ref,o.contract_version,o.aggregate_id,
                       o.correlation_id,o.status,o.lock_version,
                       l.status AS link_status,l.lock_version AS link_lock_version
                FROM outbox_event o
                LEFT JOIN workflow_start_link l ON l.tenant_id=o.tenant_id
                  AND l.ticket_id=o.aggregate_id
                WHERE o.tenant_id=? AND o.outbox_id=?
                """ + (lock ? " FOR UPDATE" : ""), (rs, n) -> new OutboxSource(
                        Uuids.toUuid(rs.getBytes("outbox_id")), rs.getString("contract_ref"),
                        rs.getInt("contract_version"), Uuids.toUuid(rs.getBytes("aggregate_id")),
                        Uuids.toUuid(rs.getBytes("correlation_id")), rs.getString("status"),
                        rs.getLong("lock_version"), rs.getString("link_status"),
                        rs.getLong("link_lock_version")), Uuids.toBytes(tenantId),
                Uuids.toBytes(outboxId)).stream().findFirst();
    }

    Optional<Audit> audit(UUID tenantId, UUID auditId) {
        return jdbc.query("""
                SELECT action_code,target_id,outcome,actor_type,actor_id,reason,error_code,
                       correlation_id,causation_id FROM audit_record
                WHERE tenant_id=? AND audit_id=?
                """, (rs, n) -> new Audit(rs.getString("action_code"),
                        Uuids.toUuid(rs.getBytes("target_id")), rs.getString("outcome"),
                        rs.getString("actor_type"), Uuids.toUuid(rs.getBytes("actor_id")),
                        rs.getString("reason"), rs.getString("error_code"),
                        Uuids.toUuid(rs.getBytes("correlation_id")),
                        Uuids.toUuid(rs.getBytes("causation_id"))),
                Uuids.toBytes(tenantId), Uuids.toBytes(auditId)).stream().findFirst();
    }

    int acceptInbox(UUID tenantId, Recovery recovery, UUID actorId, String reason,
                    UUID attemptCorrelation, Instant now) {
        return markReplaying(tenantId, recovery, actorId, reason, attemptCorrelation, now);
    }

    int acceptOutbox(UUID tenantId, Recovery recovery, OutboxSource source, UUID actorId,
                     String reason, UUID attemptCorrelation, Instant now) {
        int changed = markReplaying(tenantId, recovery, actorId, reason, attemptCorrelation, now);
        if (changed != 1) return changed;
        int outbox = jdbc.update("""
                UPDATE outbox_event SET status='PENDING',attempt_count=0,next_attempt_at=?,
                    locked_by=NULL,locked_at=NULL,last_error_code=NULL,broker_confirmed_at=NULL,
                    lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND outbox_id=? AND status='RECOVERY_REQUIRED' AND lock_version=?
                """, Timestamp.from(now), Timestamp.from(now), Uuids.toBytes(tenantId),
                Uuids.toBytes(source.outboxId()), source.lockVersion());
        if (outbox != 1) throw new IllegalStateException("Ticket Outbox replay CAS lost");
        if (!"flowdesk.workflow.start.v1".equals(source.contractRef())) return 1;
        int link = jdbc.update("""
                UPDATE workflow_start_link SET status='REQUESTED',recovery_required_at=NULL,
                    recovery_reason_code=NULL,lock_version=lock_version+1,updated_at=?,
                    updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND ticket_id=? AND status='RECOVERY_REQUIRED' AND lock_version=?
                """, Timestamp.from(now), Uuids.toBytes(tenantId), Uuids.toBytes(source.ticketId()),
                source.linkLockVersion());
        if (link != 1) throw new IllegalStateException("Ticket WorkflowStartLink replay CAS lost");
        return 1;
    }

    private int markReplaying(UUID tenantId, Recovery recovery, UUID actorId, String reason,
                              UUID attemptCorrelation, Instant now) {
        return jdbc.update("""
                UPDATE message_recovery SET state='REPLAYING',next_attempt_at=NULL,
                    replay_count=replay_count+1,last_replayed_at=?,last_replayed_by_type='USER',
                    last_replayed_by_id=?,last_replay_reason=?,last_replay_correlation_id=?,
                    lock_version=lock_version+1,updated_at=?,updated_by_type='USER',updated_by_id=?
                WHERE tenant_id=? AND recovery_id=? AND direction=? AND source_record_id=?
                  AND state='OPEN' AND lock_version=?
                """, Timestamp.from(now), Uuids.toBytes(actorId), reason,
                Uuids.toBytes(attemptCorrelation), Timestamp.from(now), Uuids.toBytes(actorId),
                Uuids.toBytes(tenantId), Uuids.toBytes(recovery.recoveryId()), recovery.direction(),
                Uuids.toBytes(recovery.sourceRecordId()), recovery.lockVersion());
    }

    int reopen(UUID tenantId, UUID recoveryId, UUID attemptCorrelation,
               String error, Instant now) {
        return jdbc.update("""
                UPDATE message_recovery SET state='OPEN',last_error_code=?,
                    last_error_summary='Controlled same-path replay did not reach a local terminal result.',
                    lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND recovery_id=? AND state='REPLAYING'
                  AND last_replay_correlation_id=?
                """, error, Timestamp.from(now), Uuids.toBytes(tenantId),
                Uuids.toBytes(recoveryId), Uuids.toBytes(attemptCorrelation));
    }

    void insertAudit(UUID tenantId, UUID auditId, String action, UUID recoveryId,
                     String outcome, UUID actorId, String reason, String error,
                     UUID correlation, UUID causation, Instant now) {
        jdbc.update("""
                INSERT INTO audit_record
                  (tenant_id,audit_id,action_code,target_type,target_id,outcome,actor_type,
                   actor_id,reason,error_code,correlation_id,causation_id,occurred_at)
                VALUES (?,?,?,'MESSAGE_RECOVERY',?,?,'USER',?,?,?,?,?,?)
                """, Uuids.toBytes(tenantId), Uuids.toBytes(auditId), action,
                Uuids.toBytes(recoveryId), outcome, Uuids.toBytes(actorId), reason, error,
                Uuids.toBytes(correlation), Uuids.toBytes(causation), Timestamp.from(now));
    }

    static TicketRecoveryView view(Recovery fact) {
        var replayedBy = fact.lastReplayedById() == null ? null
                : new TicketRecoveryView.ReplayedBy(fact.lastReplayedByType(), fact.lastReplayedById());
        return new TicketRecoveryView(fact.recoveryId(), "TICKET", fact.direction(),
                fact.sourceRecordId(), HexFormat.of().formatHex(fact.businessIntentKey()),
                fact.state(), fact.attemptCount(), fact.nextAttemptAt(), fact.lastErrorCode(),
                fact.lastErrorSummary(), fact.deadLetteredAt(), fact.recoveryRequiredAt(),
                fact.replayCount(), fact.lastReplayedAt(), replayedBy, fact.lastReplayReason(),
                fact.resolvedAt(), fact.lockVersion(), fact.createdAt(), fact.updatedAt());
    }

    private static Instant instant(ResultSet rs, String field) throws SQLException {
        Timestamp value = rs.getTimestamp(field); return value == null ? null : value.toInstant();
    }
    private static final org.springframework.jdbc.core.RowMapper<Recovery> RECOVERY_MAPPER =
            (rs, n) -> new Recovery(Uuids.toUuid(rs.getBytes("recovery_id")),
                    rs.getString("direction"), Uuids.toUuid(rs.getBytes("source_record_id")),
                    rs.getBytes("business_intent_key"), rs.getString("state"),
                    rs.getInt("attempt_count"), instant(rs, "next_attempt_at"),
                    rs.getString("last_error_code"), rs.getString("last_error_summary"),
                    instant(rs, "dead_lettered_at"), instant(rs, "recovery_required_at"),
                    rs.getInt("replay_count"), instant(rs, "last_replayed_at"),
                    rs.getString("last_replayed_by_type"),
                    Uuids.toUuid(rs.getBytes("last_replayed_by_id")),
                    rs.getString("last_replay_reason"),
                    Uuids.toUuid(rs.getBytes("last_replay_correlation_id")),
                    instant(rs, "resolved_at"), rs.getLong("lock_version"),
                    instant(rs, "created_at"), instant(rs, "updated_at"));

    record Recovery(UUID recoveryId, String direction, UUID sourceRecordId,
                    byte[] businessIntentKey, String state, int attemptCount,
                    Instant nextAttemptAt, String lastErrorCode, String lastErrorSummary,
                    Instant deadLetteredAt, Instant recoveryRequiredAt, int replayCount,
                    Instant lastReplayedAt, String lastReplayedByType, UUID lastReplayedById,
                    String lastReplayReason, UUID lastReplayCorrelationId, Instant resolvedAt,
                    long lockVersion, Instant createdAt, Instant updatedAt) { }
    record InboxSource(UUID inboxId, UUID messageId, byte[] businessIntentKey,
                       String contractRef, int contractVersion, byte[] bodyBytes, byte[] bodySha256,
                       UUID correlationId, UUID causationId, Instant occurredAt,
                       String status, long lockVersion) { }
    record OutboxSource(UUID outboxId, String contractRef, int contractVersion, UUID ticketId,
                        UUID correlationId, String status, long lockVersion,
                        String linkStatus, long linkLockVersion) { }
    record Audit(String action, UUID targetId, String outcome, String actorType, UUID actorId,
                 String reason, String errorCode, UUID correlationId, UUID causationId) { }
}
