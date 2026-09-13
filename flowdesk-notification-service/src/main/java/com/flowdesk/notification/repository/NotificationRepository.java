package com.flowdesk.notification.repository;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.notification.NotificationWireCodec;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.notification.event.AcceptedNotification;
import com.flowdesk.notification.event.NotificationContract;
import com.flowdesk.notification.event.NotificationEnvelope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Tenant-first JDBC boundary for Notification, Inbox and Recovery facts. */
@Repository
@Primary
public class NotificationRepository {
    private final JdbcTemplate jdbc;
    private final boolean rawInboxBodyReadable;

    @Autowired
    public NotificationRepository(@Qualifier("jdbcTemplate") JdbcTemplate jdbc) {
        this(jdbc, false);
    }

    /**
     * The normal service principal must not SELECT the replay source bytes.
     * The recovery-only repository uses the protected constructor with access.
     */
    protected NotificationRepository(JdbcTemplate jdbc, boolean rawInboxBodyReadable) {
        this.jdbc = jdbc;
        this.rawInboxBodyReadable = rawInboxBodyReadable;
    }

    public Optional<NotificationRow> findNotification(UUID tenantId, UUID notificationId,
                                                       boolean lock) {
        requireTenant(tenantId);
        return jdbc.query(notificationSelect() + " WHERE tenant_id=? AND notification_id=?"
                        + (lock ? " FOR UPDATE" : ""), NOTIFICATION_MAPPER,
                bytes(tenantId), bytes(notificationId)).stream().findFirst();
    }

    public Optional<NotificationRow> findByEffect(UUID tenantId, UUID recipientId,
                                                   String sourceService, byte[] sourceEffect,
                                                   String kind, boolean lock) {
        requireTenant(tenantId);
        return jdbc.query(notificationSelect()
                        + " WHERE tenant_id=? AND recipient_user_id=? AND source_service=?"
                        + " AND source_effect_key=? AND notification_kind=?"
                        + (lock ? " FOR UPDATE" : ""), NOTIFICATION_MAPPER,
                bytes(tenantId), bytes(recipientId), sourceService, sourceEffect, kind)
                .stream().findFirst();
    }

    public List<NotificationRow> listNotifications(UUID tenantId, String state, String kind,
                                                    Instant from, Instant to, boolean ascending,
                                                    Instant cursorValue, UUID cursorId,
                                                    int limit) {
        requireTenant(tenantId);
        String cmp = ascending ? ">" : "<";
        StringBuilder sql = new StringBuilder(notificationSelect())
                .append(" WHERE tenant_id=? AND recipient_user_id=?");
        List<Object> args = new ArrayList<>();
        args.add(bytes(tenantId));
        args.add(bytes(currentActor()));
        if (state != null) { sql.append(" AND state=?"); args.add(state); }
        if (kind != null) { sql.append(" AND notification_kind=?"); args.add(kind); }
        if (from != null) { sql.append(" AND created_at>=?"); args.add(ts(from)); }
        if (to != null) { sql.append(" AND created_at<=?"); args.add(ts(to)); }
        if (cursorValue != null && cursorId != null) {
            sql.append(" AND (created_at ").append(cmp)
                    .append(" ? OR (created_at = ? AND notification_id ")
                    .append(cmp).append(" ?))");
            args.add(ts(cursorValue)); args.add(ts(cursorValue)); args.add(bytes(cursorId));
        }
        sql.append(" ORDER BY created_at ").append(ascending ? "ASC" : "DESC")
                .append(", notification_id ").append(ascending ? "ASC" : "DESC")
                .append(" LIMIT ").append(Math.max(1, Math.min(101, limit)));
        return jdbc.query(sql.toString(), NOTIFICATION_MAPPER, args.toArray());
    }

    public long countUnread(UUID tenantId, UUID recipientId) {
        requireTenant(tenantId);
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification
                WHERE tenant_id=? AND recipient_user_id=? AND state='UNREAD'
                """, Long.class, bytes(tenantId), bytes(recipientId));
        return count == null ? 0 : count;
    }

    public void insertNotification(UUID tenantId, UUID notificationId, UUID recipientId,
                                    String kind, String sourceService, byte[] sourceEffect,
                                    String sourceRefType, UUID sourceRefId, String title,
                                    String body, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        jdbc.update("""
                INSERT INTO notification
                  (tenant_id,notification_id,recipient_user_id,notification_kind,
                   source_service,source_effect_key,source_ref_type,source_ref_id,title,body,
                   state,read_at,lock_version,created_at,created_by_type,created_by_id,
                   updated_at,updated_by_type,updated_by_id)
                VALUES (?,?,?,?,?,?,?,?,?,?, 'UNREAD',NULL,0,?,'SERVICE',NULL,?,'SERVICE',NULL)
                """, bytes(tenantId), bytes(notificationId), bytes(recipientId), kind,
                sourceService, sourceEffect, sourceRefType, bytes(sourceRefId), title, body,
                ts(now), ts(now));
    }

    public int updateReadState(UUID tenantId, UUID notificationId, UUID recipientId,
                               long expectedVersion, String state, Instant readAt,
                               Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        return jdbc.update("""
                UPDATE notification
                SET state=?, read_at=?, lock_version=lock_version+1, updated_at=?,
                    updated_by_type='USER', updated_by_id=?
                WHERE tenant_id=? AND notification_id=? AND recipient_user_id=?
                  AND lock_version=?
                """, state, ts(readAt), ts(now), bytes(recipientId), bytes(tenantId),
                bytes(notificationId), bytes(recipientId), expectedVersion);
    }

    public Optional<AuditRow> findAudit(UUID tenantId, UUID auditId) {
        requireTenant(tenantId);
        return jdbc.query("""
                SELECT audit_id,action_code,target_type,target_id,outcome,actor_type,actor_id,
                       reason,error_code,correlation_id,causation_id,occurred_at,recorded_at
                FROM audit_record WHERE tenant_id=? AND audit_id=?
                """, AUDIT_MAPPER, bytes(tenantId), bytes(auditId)).stream().findFirst();
    }

    public void insertAudit(UUID tenantId, UUID auditId, String actionCode, String targetType,
                            UUID targetId, String outcome, String actorType, UUID actorId,
                            String reason, String errorCode, UUID correlationId,
                            UUID causationId, Instant occurredAt) {
        requireTenant(tenantId);
        requireTransaction();
        jdbc.update("""
                INSERT INTO audit_record
                  (tenant_id,audit_id,action_code,target_type,target_id,outcome,actor_type,
                   actor_id,reason,error_code,correlation_id,causation_id,occurred_at,recorded_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, bytes(tenantId), bytes(auditId), actionCode, targetType, bytes(targetId),
                outcome, actorType, bytes(actorId), reason, errorCode, bytes(correlationId),
                bytes(causationId), ts(occurredAt), ts(occurredAt));
    }

    // ----- Inbox -----------------------------------------------------------------

    public Duplicate findDuplicate(AcceptedNotification message, boolean lock) {
        return findDuplicate(message.envelope(), lock);
    }

    /** Looks up duplicates using only the complete immutable transport envelope. */
    public Duplicate findDuplicate(NotificationEnvelope message, boolean lock) {
        requireTenant(message.tenantId());
        String suffix = lock ? " FOR UPDATE" : "";
        Optional<InboxRow> byMessage = jdbc.query(inboxSelect()
                        + " WHERE tenant_id=? AND producer_service=? AND message_id=?" + suffix,
                INBOX_MAPPER, bytes(message.tenantId()), message.binding().producerService(),
                bytes(message.messageId())).stream().findFirst();
        if (byMessage.isPresent()) return classify(message, byMessage.get(), "MESSAGE_ID_CONTENT_CONFLICT");
        Optional<InboxRow> byIntent = jdbc.query(inboxSelect()
                        + " WHERE tenant_id=? AND producer_service=? AND contract_ref=?"
                        + " AND business_intent_key=?" + suffix, INBOX_MAPPER,
                bytes(message.tenantId()), message.binding().producerService(),
                message.binding().contractRef(), message.businessIntentKey()).stream().findFirst();
        if (byIntent.isPresent()) {
            return classify(message, byIntent.get(), "BUSINESS_INTENT_CONTENT_CONFLICT");
        }
        Optional<InboxRow> byPublication = findInboxByPublication(
                message.tenantId(), message.publicationKey(), lock);
        return byPublication.map(row -> classify(message, row,
                        "PUBLICATION_CONTENT_CONFLICT"))
                .orElseGet(Duplicate::none);
    }

    private static Duplicate classify(NotificationEnvelope message, InboxRow row, String code) {
        boolean same = Objects.equals(message.binding().producerService(), row.producerService())
                && Objects.equals(message.messageId(), row.messageId())
                && Objects.equals(message.messageKey(), row.messageKey())
                && Objects.equals(message.binding().topic(), row.topic())
                && Objects.equals(message.binding().consumerGroup(), row.consumerGroup())
                && Objects.equals(message.logicalDestination(), row.logicalDestination())
                && "INBOX".equals(row.direction())
                && Objects.equals(message.binding().contractRef(), row.contractRef())
                && row.contractVersion() == NotificationEventContract.VERSION
                && Objects.equals(message.aggregateType(), row.aggregateType())
                && Objects.equals(message.aggregateId(), row.aggregateId())
                && Arrays.equals(message.businessIntentKey(), row.businessIntentKey())
                && Arrays.equals(message.publicationKey(), row.publicationKey())
                && Arrays.equals(message.bodySha256(), row.bodySha256())
                // Runtime code deliberately projects body_bytes as NULL. The
                // Envelope's SHA-256 was validated before this lookup and is
                // the immutable duplicate comparator in that least-privilege path.
                && (row.bodyBytes() == null || Arrays.equals(message.bodyBytes(), row.bodyBytes()))
                && Objects.equals(message.correlationId(), row.correlationId())
                && Objects.equals(message.causationId(), row.causationId())
                && Objects.equals(message.occurredAt(), row.occurredAt());
        if (!same) return new Duplicate(DuplicateKind.CONFLICT, row, code);
        if ("RECOVERY_REQUIRED".equals(row.status())) {
            return new Duplicate(DuplicateKind.RECOVERABLE, row, null);
        }
        return new Duplicate(DuplicateKind.TERMINAL, row, null);
    }

    public void insertProcessedInbox(AcceptedNotification message, UUID inboxId,
                                     UUID resultId, Instant now) {
        insertInbox(message.envelope(), inboxId, "PROCESSED", "NOTIFICATION", resultId,
                1, null, now, now);
    }

    public void insertRejectedInbox(AcceptedNotification message, UUID inboxId,
                                     String code, Instant now) {
        insertRejectedInbox(message.envelope(), inboxId, code, now);
    }

    /** Persists a deterministic rejection after the envelope trust boundary. */
    public void insertRejectedInbox(NotificationEnvelope message, UUID inboxId,
                                    String code, Instant now) {
        insertInbox(message, inboxId, "REJECTED", null, null, 1, code, now, now);
    }

    public void insertRecoveryInbox(AcceptedNotification message, UUID inboxId,
                                     String code, Instant now) {
        insertInbox(message.envelope(), inboxId, "RECOVERY_REQUIRED", null, null,
                NotificationContract.DELIVERY_ATTEMPTS, code, now, null);
    }

    private void insertInbox(NotificationEnvelope message, UUID inboxId, String status,
                              String resultType, UUID resultId, int attempts, String error,
                              Instant now, Instant processedAt) {
        requireTenant(message.tenantId());
        requireTransaction();
        UUID expectedInboxId = uuidFromKey(NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-INBOX-V1", message.tenantId(), message.messageId()));
        if (!expectedInboxId.equals(inboxId)) {
            throw new IllegalStateException("Notification Inbox identity is not deterministic");
        }
        try {
            jdbc.update("""
                INSERT INTO inbox_message
                  (tenant_id,inbox_id,producer_service,message_id,message_key,business_intent_key,
                   publication_key,contract_ref,contract_version,aggregate_type,aggregate_id,
                   body_sha256,body_bytes,topic,consumer_group,logical_destination,direction,
                   correlation_id,causation_id,occurred_at,status,result_type,result_id,
                   attempt_count,last_error_code,first_received_at,last_attempt_at,processed_at,
                   lock_version,created_at,updated_at,updated_by_type,updated_by_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, bytes(message.tenantId()), bytes(inboxId), message.binding().producerService(),
                bytes(message.messageId()), message.messageKey(), message.businessIntentKey(),
                message.publicationKey(), message.binding().contractRef(), NotificationContract.VERSION,
                message.aggregateType(), bytes(message.aggregateId()), message.bodySha256(),
                message.bodyBytes(), message.topic(), message.consumerGroup(),
                message.logicalDestination(), "INBOX", bytes(message.correlationId()),
                bytes(message.causationId()), ts(message.occurredAt()), status, resultType,
                bytes(resultId), attempts, error, ts(now), ts(now), ts(processedAt), 0,
                ts(now), ts(now), "SERVICE", null);
        }
        catch (DuplicateKeyException duplicate) {
            InboxRow existing = findInbox(message.tenantId(), expectedInboxId, true)
                    .orElseThrow(() -> duplicate);
            if (!sameSource(message, existing)
                    || !Objects.equals(existing.status(), status)
                    || !Objects.equals(existing.resultType(), resultType)
                    || !Objects.equals(existing.resultId(), resultId)
                    || !Objects.equals(existing.lastErrorCode(), error)) {
                throw new IllegalStateException("Notification Inbox identity/content conflict", duplicate);
            }
        }
    }

    private static boolean sameSource(NotificationEnvelope message, InboxRow row) {
        return Objects.equals(message.binding().producerService(), row.producerService())
                && Objects.equals(message.messageId(), row.messageId())
                && Objects.equals(message.messageKey(), row.messageKey())
                && Objects.equals(message.binding().topic(), row.topic())
                && Objects.equals(message.binding().consumerGroup(), row.consumerGroup())
                && Objects.equals(message.logicalDestination(), row.logicalDestination())
                && "INBOX".equals(row.direction())
                && Objects.equals(message.binding().contractRef(), row.contractRef())
                && row.contractVersion() == NotificationEventContract.VERSION
                && Objects.equals(message.aggregateType(), row.aggregateType())
                && Objects.equals(message.aggregateId(), row.aggregateId())
                && Arrays.equals(message.businessIntentKey(), row.businessIntentKey())
                && Arrays.equals(message.publicationKey(), row.publicationKey())
                && Arrays.equals(message.bodySha256(), row.bodySha256())
                && (row.bodyBytes() == null || Arrays.equals(message.bodyBytes(), row.bodyBytes()))
                && Objects.equals(message.correlationId(), row.correlationId())
                && Objects.equals(message.causationId(), row.causationId())
                && Objects.equals(message.occurredAt(), row.occurredAt());
    }

    public int completeRecoveredInbox(AcceptedNotification message, InboxRow row,
                                      UUID resultId, Instant now) {
        return completeRecoveredInbox(message.envelope(), row, resultId, now);
    }

    public int completeRecoveredInbox(NotificationEnvelope message, InboxRow row,
                                      UUID resultId, Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        return jdbc.update("""
                UPDATE inbox_message SET status='PROCESSED',result_type='NOTIFICATION',result_id=?,
                  attempt_count=attempt_count+1,last_error_code=NULL,processed_at=?,last_attempt_at=?,
                  lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND inbox_id=? AND status='RECOVERY_REQUIRED' AND lock_version=?
                """, bytes(resultId), ts(now), ts(now), ts(now), bytes(message.tenantId()),
                bytes(row.inboxId()), row.lockVersion());
    }

    public int rejectRecoveredInbox(AcceptedNotification message, InboxRow row,
                                    String code, Instant now) {
        return rejectRecoveredInbox(message.envelope(), row, code, now);
    }

    public int rejectRecoveredInbox(NotificationEnvelope message, InboxRow row,
                                    String code, Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        return jdbc.update("""
                UPDATE inbox_message SET status='REJECTED',attempt_count=attempt_count+1,
                  last_error_code=?,processed_at=?,last_attempt_at=?,lock_version=lock_version+1,
                  updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND inbox_id=? AND status='RECOVERY_REQUIRED' AND lock_version=?
                """, code, ts(now), ts(now), ts(now), bytes(message.tenantId()),
                bytes(row.inboxId()), row.lockVersion());
    }

    // ----- Recovery --------------------------------------------------------------

    public void insertRecovery(UUID tenantId, UUID recoveryId, UUID inboxId,
                               byte[] businessIntent, String errorCode, String summary,
                               Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        UUID expectedRecoveryId = uuidFromKey(NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-RECOVERY-V1", tenantId, inboxId));
        if (!expectedRecoveryId.equals(recoveryId)) {
            throw new IllegalStateException("Notification Recovery identity is not deterministic");
        }
        InboxRow source = findInbox(tenantId, inboxId, true)
                .orElseThrow(() -> new IllegalStateException("Notification Recovery source Inbox is missing"));
        if (!"RECOVERY_REQUIRED".equals(source.status())
                || !Arrays.equals(businessIntent, source.businessIntentKey())) {
            throw new IllegalStateException("Notification Recovery source/content conflict");
        }
        try {
            jdbc.update("""
                    INSERT INTO message_recovery
                      (tenant_id,recovery_id,direction,source_record_id,business_intent_key,state,
                       attempt_count,next_attempt_at,last_error_code,last_error_summary,dead_lettered_at,
                       recovery_required_at,replay_count,last_replayed_at,last_replayed_by_type,
                       last_replayed_by_id,last_replay_reason,last_replay_correlation_id,resolved_at,
                       lock_version,created_at,updated_at,updated_by_type,updated_by_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, bytes(tenantId), bytes(recoveryId), "INBOX", bytes(inboxId), businessIntent,
                    "OPEN", NotificationContract.DELIVERY_ATTEMPTS, null, errorCode, summary, null,
                    ts(now), 0, null, null, null, null, null, null, 0, ts(now), ts(now),
                    "SERVICE", null);
        }
        catch (DuplicateKeyException duplicate) {
            // A DLQ redelivery can race another acceptor on either the
            // deterministic recovery id or the one-to-one source key.  Read
            // the committed row and compare immutable content before
            // absorbing the duplicate; never turn a conflicting recovery into
            // a successful retry.
            RecoveryRow existing = findRecoveryBySource(tenantId, inboxId, true)
                    .orElseThrow(() -> duplicate);
            if (!expectedRecoveryId.equals(existing.recoveryId())
                    || !"INBOX".equals(existing.direction())
                    || !inboxId.equals(existing.sourceRecordId())
                    || !Arrays.equals(existing.businessIntentKey(), businessIntent)
                    || existing.attemptCount() != NotificationContract.DELIVERY_ATTEMPTS
                    || !Objects.equals(existing.lastErrorCode(), errorCode)
                    || !Objects.equals(existing.lastErrorSummary(), summary)
                    || !java.util.Set.of("OPEN", "REPLAYING", "RESOLVED").contains(existing.state())) {
                throw new IllegalStateException("Notification Recovery identity/content conflict", duplicate);
            }
        }
    }

    public List<RecoveryRow> listRecoveries(UUID tenantId, String direction, String state,
                                             Boolean deadLettered, String errorCode,
                                             Instant from, Instant to, boolean ascending,
                                             Instant cursorValue, UUID cursorId, int limit) {
        requireTenant(tenantId);
        String cmp = ascending ? ">" : "<";
        StringBuilder sql = new StringBuilder(recoverySelect()).append(" WHERE tenant_id=? AND direction='INBOX'");
        List<Object> args = new ArrayList<>(); args.add(bytes(tenantId));
        if (direction != null) { sql.append(" AND direction=?"); args.add(direction); }
        if (state != null) { sql.append(" AND state=?"); args.add(state); }
        if (deadLettered != null) sql.append(deadLettered ? " AND dead_lettered_at IS NOT NULL" : " AND dead_lettered_at IS NULL");
        if (errorCode != null) { sql.append(" AND last_error_code=?"); args.add(errorCode); }
        if (from != null) { sql.append(" AND recovery_required_at>=?"); args.add(ts(from)); }
        if (to != null) { sql.append(" AND recovery_required_at<=?"); args.add(ts(to)); }
        if (cursorValue != null && cursorId != null) {
            sql.append(" AND (recovery_required_at ").append(cmp)
                    .append(" ? OR (recovery_required_at=? AND recovery_id ").append(cmp).append(" ?))");
            args.add(ts(cursorValue)); args.add(ts(cursorValue)); args.add(bytes(cursorId));
        }
        sql.append(" ORDER BY recovery_required_at ").append(ascending ? "ASC" : "DESC")
                .append(", recovery_id ").append(ascending ? "ASC" : "DESC")
                .append(" LIMIT ").append(Math.max(1, Math.min(101, limit)));
        return jdbc.query(sql.toString(), RECOVERY_MAPPER, args.toArray());
    }

    public Optional<RecoveryRow> findRecovery(UUID tenantId, UUID recoveryId, boolean lock) {
        requireTenant(tenantId);
        return jdbc.query(recoverySelect() + " WHERE tenant_id=? AND recovery_id=?"
                        + (lock ? " FOR UPDATE" : ""), RECOVERY_MAPPER,
                bytes(tenantId), bytes(recoveryId)).stream().findFirst();
    }

    public int claimReplay(UUID tenantId, UUID recoveryId, long expectedVersion,
                           UUID actorId, UUID replayCorrelation, String reason, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        return jdbc.update("""
                UPDATE message_recovery
                SET state='REPLAYING',next_attempt_at=NULL,replay_count=replay_count+1,
                    last_replayed_at=?,last_replayed_by_type='USER',last_replayed_by_id=?,
                    last_replay_reason=?,last_replay_correlation_id=?,lock_version=lock_version+1,
                    updated_at=?,updated_by_type='USER',updated_by_id=?
                WHERE tenant_id=? AND recovery_id=? AND state='OPEN' AND lock_version=?
                """, ts(now), bytes(actorId), reason, bytes(replayCorrelation), ts(now),
                bytes(actorId), bytes(tenantId), bytes(recoveryId), expectedVersion);
    }

    public int resolveRecovery(UUID tenantId, UUID recoveryId, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        RecoveryRow current = findRecovery(tenantId, recoveryId, true)
                .orElseThrow(() -> new IllegalStateException("Notification Recovery row is missing"));
        if ("RESOLVED".equals(current.state())) return 0;
        return resolveRecovery(tenantId, recoveryId, current.lockVersion(), now);
    }

    /** Resolves exactly the version that the caller locked. */
    public int resolveRecovery(UUID tenantId, UUID recoveryId, long expectedVersion,
                               Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        return jdbc.update("""
                UPDATE message_recovery SET state='RESOLVED',resolved_at=?,next_attempt_at=NULL,
                  lock_version=lock_version+1,updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND recovery_id=? AND state IN ('OPEN','REPLAYING')
                  AND lock_version=?
                """, ts(now), ts(now), bytes(tenantId), bytes(recoveryId), expectedVersion);
    }

    /**
     * Resolves a recovery only when its immutable Inbox source is already a
     * terminal fact. An ACK from a reconstructed delivery is not sufficient
     * proof by itself because a pre-trust envelope rejection has no database
     * side effect.
     */
    public int resolveRecoveryIfSourceTerminal(UUID tenantId, UUID recoveryId,
                                               long expectedVersion, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        return jdbc.update("""
                UPDATE message_recovery r
                SET r.state='RESOLVED',r.resolved_at=?,r.next_attempt_at=NULL,
                    r.lock_version=r.lock_version+1,r.updated_at=?,
                    r.updated_by_type='SERVICE',r.updated_by_id=NULL
                WHERE r.tenant_id=? AND r.recovery_id=? AND r.direction='INBOX'
                  AND r.state IN ('OPEN','REPLAYING') AND r.lock_version=?
                  AND EXISTS (
                    SELECT 1 FROM inbox_message i
                    WHERE i.tenant_id=r.tenant_id AND i.inbox_id=r.source_record_id
                      AND i.direction='INBOX' AND i.status IN ('PROCESSED','REJECTED')
                  )
                """, ts(now), ts(now), bytes(tenantId), bytes(recoveryId), expectedVersion);
    }

    public int reopenRecovery(UUID tenantId, UUID recoveryId, String errorCode,
                              String summary, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        RecoveryRow current = findRecovery(tenantId, recoveryId, true)
                .orElseThrow(() -> new IllegalStateException("Notification Recovery row is missing"));
        if (!"REPLAYING".equals(current.state())) return 0;
        return reopenRecovery(tenantId, recoveryId, current.lockVersion(), errorCode, summary, now);
    }

    /** Reopens exactly the version claimed by a replay worker. */
    public int reopenRecovery(UUID tenantId, UUID recoveryId, long expectedVersion,
                              String errorCode, String summary, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        return jdbc.update("""
                UPDATE message_recovery SET state='OPEN',next_attempt_at=NULL,last_error_code=?,
                  last_error_summary=?,lock_version=lock_version+1,updated_at=?,
                  updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND recovery_id=? AND state='REPLAYING' AND lock_version=?
                """, errorCode, summary, ts(now), bytes(tenantId), bytes(recoveryId), expectedVersion);
    }

    /**
     * Permanently closes a Recovery whose persisted Inbox source cannot be
     * reconstructed safely.  The state remains one of the frozen lifecycle
     * states (RESOLVED) while the explicit error code makes the terminal
     * non-replayable decision observable and prevents a poison loop.
     */
    public int markNonReplayable(UUID tenantId, UUID recoveryId, long expectedVersion,
                                 String errorCode, String summary, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        return jdbc.update("""
                UPDATE message_recovery
                SET state='RESOLVED',resolved_at=?,next_attempt_at=NULL,
                    last_error_code=?,last_error_summary=?,lock_version=lock_version+1,
                    updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND recovery_id=? AND state IN ('OPEN','REPLAYING')
                  AND lock_version=?
                """, ts(now), errorCode, summary, ts(now), bytes(tenantId),
                bytes(recoveryId), expectedVersion);
    }

    public Optional<InboxRow> findInbox(UUID tenantId, UUID inboxId, boolean lock) {
        requireTenant(tenantId);
        return jdbc.query(inboxSelect() + " WHERE tenant_id=? AND inbox_id=?"
                        + (lock ? " FOR UPDATE" : ""), INBOX_MAPPER,
                bytes(tenantId), bytes(inboxId)).stream().findFirst();
    }

    /** Exact source lookup used to settle the one-to-one Inbox Recovery row. */
    public Optional<RecoveryRow> findRecoveryBySource(UUID tenantId, UUID sourceRecordId,
                                                       boolean lock) {
        requireTenant(tenantId);
        return jdbc.query(recoverySelect()
                        + " WHERE tenant_id=? AND direction='INBOX' AND source_record_id=?"
                        + (lock ? " FOR UPDATE" : ""), RECOVERY_MAPPER,
                bytes(tenantId), bytes(sourceRecordId)).stream().findFirst();
    }

    /** Exact publication lookup used to detect duplicate outbox effects. */
    public Optional<InboxRow> findInboxByPublication(UUID tenantId, byte[] publicationKey,
                                                     boolean lock) {
        requireTenant(tenantId);
        return jdbc.query(inboxSelect()
                        + " WHERE tenant_id=? AND publication_key=?"
                        + (lock ? " FOR UPDATE" : ""), INBOX_MAPPER,
                bytes(tenantId), publicationKey).stream().findFirst();
    }

    private static String notificationSelect() {
        return """
                SELECT notification_id,recipient_user_id,notification_kind,source_service,
                       source_effect_key,source_ref_type,source_ref_id,title,body,state,read_at,
                       lock_version,created_at,updated_at
                FROM notification
                """;
    }

    private String inboxSelect() {
        return ("""
                SELECT inbox_id,producer_service,message_id,message_key,business_intent_key,
                       publication_key,contract_ref,contract_version,aggregate_type,aggregate_id,
                       body_sha256,%s,topic,consumer_group,logical_destination,direction,
                       correlation_id,causation_id,occurred_at,status,result_type,result_id,
                       attempt_count,last_error_code,first_received_at,last_attempt_at,processed_at,
                       lock_version,created_at,updated_at
                FROM inbox_message
                """).formatted(rawInboxBodyReadable ? "body_bytes" : "NULL AS body_bytes");
    }

    private static String recoverySelect() {
        return """
                SELECT recovery_id,direction,source_record_id,business_intent_key,state,attempt_count,
                       next_attempt_at,last_error_code,last_error_summary,dead_lettered_at,
                       recovery_required_at,replay_count,last_replayed_at,last_replayed_by_type,
                       last_replayed_by_id,last_replay_reason,last_replay_correlation_id,resolved_at,
                       lock_version,created_at,updated_at
                FROM message_recovery
                """;
    }

    private static UUID currentActor() {
        var actor = TenantContextHolder.current().actor();
        if (actor instanceof com.flowdesk.common.tenant.ActorRef.Human human) return human.userId();
        throw new IllegalStateException("Notification list requires a human actor");
    }

    private static void requireTenant(UUID tenantId) {
        TenantContextHolder.current().requireTenant(tenantId);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Notification mutation requires a transaction");
        }
    }

    private static byte[] bytes(UUID value) {
        return value == null ? null : ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        if (value == null) return null;
        ByteBuffer b = ByteBuffer.wrap(value); return new UUID(b.getLong(), b.getLong());
    }

    private static UUID uuidFromKey(byte[] value) {
        if (value == null || value.length < 16) {
            throw new IllegalArgumentException("Derived identity key is too short");
        }
        return uuid(Arrays.copyOf(value, 16));
    }

    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }

    private static final org.springframework.jdbc.core.RowMapper<NotificationRow> NOTIFICATION_MAPPER =
            (rs, n) -> new NotificationRow(uuid(rs.getBytes("notification_id")),
                    uuid(rs.getBytes("recipient_user_id")), rs.getString("notification_kind"),
                    rs.getString("source_service"), rs.getBytes("source_effect_key"),
                    rs.getString("source_ref_type"), uuid(rs.getBytes("source_ref_id")),
                    rs.getString("title"), rs.getString("body"), rs.getString("state"),
                    instant(rs, "read_at"), rs.getLong("lock_version"), instant(rs, "created_at"),
                    instant(rs, "updated_at"));
    private static final org.springframework.jdbc.core.RowMapper<InboxRow> INBOX_MAPPER =
            (rs, n) -> new InboxRow(uuid(rs.getBytes("inbox_id")), rs.getString("producer_service"),
                    uuid(rs.getBytes("message_id")), rs.getString("message_key"),
                    rs.getBytes("business_intent_key"), rs.getBytes("publication_key"),
                    rs.getString("contract_ref"), rs.getInt("contract_version"),
                    rs.getString("aggregate_type"), uuid(rs.getBytes("aggregate_id")),
                    rs.getBytes("body_sha256"), rs.getBytes("body_bytes"), rs.getString("topic"),
                    rs.getString("consumer_group"), rs.getString("logical_destination"),
                    rs.getString("direction"), uuid(rs.getBytes("correlation_id")),
                    uuid(rs.getBytes("causation_id")), instant(rs, "occurred_at"),
                    rs.getString("status"), rs.getString("result_type"), uuid(rs.getBytes("result_id")),
                    rs.getInt("attempt_count"), rs.getString("last_error_code"),
                    instant(rs, "first_received_at"), instant(rs, "last_attempt_at"),
                    instant(rs, "processed_at"), rs.getLong("lock_version"), instant(rs, "created_at"),
                    instant(rs, "updated_at"));
    private static final org.springframework.jdbc.core.RowMapper<RecoveryRow> RECOVERY_MAPPER =
            (rs, n) -> new RecoveryRow(uuid(rs.getBytes("recovery_id")), rs.getString("direction"),
                    uuid(rs.getBytes("source_record_id")), rs.getBytes("business_intent_key"),
                    rs.getString("state"), rs.getInt("attempt_count"), instant(rs, "next_attempt_at"),
                    rs.getString("last_error_code"), rs.getString("last_error_summary"),
                    instant(rs, "dead_lettered_at"), instant(rs, "recovery_required_at"),
                    rs.getInt("replay_count"), instant(rs, "last_replayed_at"),
                    rs.getString("last_replayed_by_type"), uuid(rs.getBytes("last_replayed_by_id")),
                    rs.getString("last_replay_reason"), uuid(rs.getBytes("last_replay_correlation_id")),
                    instant(rs, "resolved_at"), rs.getLong("lock_version"), instant(rs, "created_at"),
                    instant(rs, "updated_at"));
    private static final org.springframework.jdbc.core.RowMapper<AuditRow> AUDIT_MAPPER =
            (rs, n) -> new AuditRow(uuid(rs.getBytes("audit_id")), rs.getString("action_code"),
                    rs.getString("target_type"), uuid(rs.getBytes("target_id")), rs.getString("outcome"),
                    rs.getString("actor_type"), uuid(rs.getBytes("actor_id")), rs.getString("reason"),
                    rs.getString("error_code"), uuid(rs.getBytes("correlation_id")),
                    uuid(rs.getBytes("causation_id")), instant(rs, "occurred_at"), instant(rs, "recorded_at"));

    public enum DuplicateKind { NONE, TERMINAL, RECOVERABLE, CONFLICT }
    public record Duplicate(DuplicateKind kind, InboxRow inbox, String conflictCode) {
        static Duplicate none() { return new Duplicate(DuplicateKind.NONE, null, null); }
    }

    public record NotificationRow(UUID notificationId, UUID recipientUserId, String notificationKind,
                                  String sourceService, byte[] sourceEffectKey, String sourceRefType,
                                  UUID sourceRefId, String title, String body, String state,
                                  Instant readAt, long lockVersion, Instant createdAt, Instant updatedAt) {
        public NotificationRow { sourceEffectKey = sourceEffectKey == null ? null : sourceEffectKey.clone(); }
        @Override public byte[] sourceEffectKey() { return sourceEffectKey == null ? null : sourceEffectKey.clone(); }
    }

    public record InboxRow(UUID inboxId, String producerService, UUID messageId, String messageKey,
                           byte[] businessIntentKey, byte[] publicationKey, String contractRef,
                           int contractVersion, String aggregateType, UUID aggregateId,
                           byte[] bodySha256, byte[] bodyBytes, String topic, String consumerGroup,
                           String logicalDestination, String direction, UUID correlationId,
                           UUID causationId, Instant occurredAt, String status, String resultType,
                           UUID resultId, int attemptCount, String lastErrorCode,
                           Instant firstReceivedAt, Instant lastAttemptAt, Instant processedAt,
                           long lockVersion, Instant createdAt, Instant updatedAt) {
        public InboxRow {
            businessIntentKey = businessIntentKey == null ? null : businessIntentKey.clone();
            publicationKey = publicationKey == null ? null : publicationKey.clone();
            bodySha256 = bodySha256 == null ? null : bodySha256.clone();
            bodyBytes = bodyBytes == null ? null : bodyBytes.clone();
        }
        @Override public byte[] businessIntentKey() { return businessIntentKey == null ? null : businessIntentKey.clone(); }
        @Override public byte[] publicationKey() { return publicationKey == null ? null : publicationKey.clone(); }
        @Override public byte[] bodySha256() { return bodySha256 == null ? null : bodySha256.clone(); }
        @Override public byte[] bodyBytes() { return bodyBytes == null ? null : bodyBytes.clone(); }
    }

    public record RecoveryRow(UUID recoveryId, String direction, UUID sourceRecordId,
                              byte[] businessIntentKey, String state, int attemptCount,
                              Instant nextAttemptAt, String lastErrorCode, String lastErrorSummary,
                              Instant deadLetteredAt, Instant recoveryRequiredAt, int replayCount,
                              Instant lastReplayedAt, String lastReplayedByType,
                              UUID lastReplayedById, String lastReplayReason,
                              UUID lastReplayCorrelationId, Instant resolvedAt, long lockVersion,
                              Instant createdAt, Instant updatedAt) {
        public RecoveryRow { businessIntentKey = businessIntentKey == null ? null : businessIntentKey.clone(); }
        @Override public byte[] businessIntentKey() { return businessIntentKey == null ? null : businessIntentKey.clone(); }
    }

    public record AuditRow(UUID auditId, String actionCode, String targetType, UUID targetId,
                           String outcome, String actorType, UUID actorId, String reason,
                           String errorCode, UUID correlationId, UUID causationId,
                           Instant occurredAt, Instant recordedAt) { }
}
