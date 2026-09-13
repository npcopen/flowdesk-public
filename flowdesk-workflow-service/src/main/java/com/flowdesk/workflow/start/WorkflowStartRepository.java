package com.flowdesk.workflow.start;

import com.flowdesk.workflow.event.WorkflowReverseEventCodec;
import com.flowdesk.workflow.event.WorkflowReverseEventContract;
import com.flowdesk.workflow.service.WorkflowIdempotencyCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.ConcurrencyFailureException;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Tenant-first persistence operations for the strict Workflow-start entry. */
final class WorkflowStartRepository {

    static final String MESSAGE_CONFLICT = "MESSAGE_ID_CONTENT_CONFLICT";
    static final String INTENT_CONFLICT = "BUSINESS_INTENT_CONTENT_CONFLICT";
    static final String VERSION_REFERENCE_INVALID = "WORKFLOW_VERSION_REFERENCE_INVALID";
    static final String VERSION_STATE_INVALID = "WORKFLOW_VERSION_STATE_INVALID";
    static final String VERSION_RULE_INVALID = "WORKFLOW_VERSION_RULE_INVALID";
    static final String INSTANCE_CONFLICT = "WORKFLOW_INSTANCE_CONTENT_CONFLICT";

    private final JdbcTemplate jdbc;

    WorkflowStartRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    DuplicateCheck findDuplicate(AcceptedWorkflowStart message, boolean lock) {
        List<InboxRecord> byMessage = jdbc.query("""
                SELECT inbox_id, message_id, business_intent_key, contract_ref,
                       contract_version, body_sha256, status, result_type, result_id,
                       lock_version
                FROM inbox_message
                WHERE tenant_id = ? AND producer_service = ? AND message_id = ?
                """ + forUpdate(lock), WorkflowStartRepository::mapInbox,
                uuid(message.tenantId()), message.producerService(), uuid(message.messageId()));
        if (!byMessage.isEmpty()) {
            InboxRecord existing = byMessage.get(0);
            return sameContent(existing, message)
                    ? DuplicateCheck.sameMessage(existing)
                    : DuplicateCheck.conflict(MESSAGE_CONFLICT, existing);
        }

        List<InboxRecord> byIntent = jdbc.query("""
                SELECT inbox_id, message_id, business_intent_key, contract_ref,
                       contract_version, body_sha256, status, result_type, result_id,
                       lock_version
                FROM inbox_message
                WHERE tenant_id = ? AND producer_service = ?
                  AND contract_ref = ? AND business_intent_key = ?
                """ + forUpdate(lock), WorkflowStartRepository::mapInbox,
                uuid(message.tenantId()), message.producerService(), message.contractRef(),
                message.businessIntentKey());
        if (!byIntent.isEmpty()) {
            InboxRecord existing = byIntent.get(0);
            return sameContent(existing, message)
                    ? DuplicateCheck.sameIntent(existing)
                    : DuplicateCheck.conflict(INTENT_CONFLICT, existing);
        }
        return DuplicateCheck.fresh();
    }

    VersionLookup findVersion(AcceptedWorkflowStart message) {
        List<VersionSnapshot> rows = jdbc.query("""
                SELECT v.workflow_version_id, v.definition_id, v.version_no,
                       v.status AS version_status, d.status AS definition_status,
                       v.condition_field_code, v.condition_operator,
                       v.condition_value_type, v.condition_value_text,
                       v.approval_level_count, v.level1_approver_user_id,
                       v.level2_approver_user_id, v.snapshot_sha256
                FROM workflow_version v
                JOIN workflow_definition d
                  ON d.tenant_id = v.tenant_id AND d.definition_id = v.definition_id
                WHERE v.tenant_id = ? AND v.workflow_version_id = ?
                  AND v.definition_id = ? AND v.version_no = ?
                """, WorkflowStartRepository::mapVersion,
                uuid(message.tenantId()), uuid(message.workflowVersionId()),
                uuid(message.workflowDefinitionId()), message.versionNo());
        if (rows.isEmpty()) {
            return VersionLookup.invalid(VERSION_REFERENCE_INVALID);
        }
        VersionSnapshot version = rows.get(0);
        if (!"PUBLISHED".equals(version.versionStatus())
                || !"ACTIVE".equals(version.definitionStatus())) {
            return VersionLookup.invalid(VERSION_STATE_INVALID);
        }
        if (!"SERVICE_RISK_LEVEL".equals(version.conditionFieldCode())
                || !"EQUALS".equals(version.conditionOperator())
                || !"STRING".equals(version.conditionValueType())
                || !("NORMAL".equals(version.conditionValueText())
                || "HIGH".equals(version.conditionValueText()))
                || version.approvalLevelCount() != 2
                || version.snapshotSha256().length != 32) {
            return VersionLookup.invalid(VERSION_RULE_INVALID);
        }
        return VersionLookup.valid(version);
    }

    ExistingInstanceDecision findExistingInstance(AcceptedWorkflowStart message,
                                                   byte[] inputSnapshotSha256,
                                                   boolean conditionResult,
                                                   boolean lock) {
        List<InstanceRecord> byTicket = findInstances("ticket_id", uuid(message.ticketId()),
                message.tenantId(), lock);
        if (!byTicket.isEmpty()) {
            InstanceRecord existing = byTicket.get(0);
            return instanceMatches(existing, message, inputSnapshotSha256, conditionResult)
                    ? ExistingInstanceDecision.same(existing.workflowInstanceId())
                    : ExistingInstanceDecision.conflict();
        }
        List<InstanceRecord> byIntent = findInstances(
                "start_intent_key", message.businessIntentKey(), message.tenantId(), lock);
        if (!byIntent.isEmpty()) {
            InstanceRecord existing = byIntent.get(0);
            return instanceMatches(existing, message, inputSnapshotSha256, conditionResult)
                    ? ExistingInstanceDecision.same(existing.workflowInstanceId())
                    : ExistingInstanceDecision.conflict();
        }
        return ExistingInstanceDecision.fresh();
    }

    void insertProcessedInbox(AcceptedWorkflowStart message,
                              UUID inboxId,
                              UUID workflowInstanceId,
                              Instant now) {
        jdbc.update("""
                INSERT INTO inbox_message
                  (tenant_id, inbox_id, producer_service, message_id, business_intent_key,
                   contract_ref, contract_version, body_bytes, body_sha256, correlation_id,
                   causation_id, occurred_at, status, result_type, result_id, attempt_count,
                   first_received_at, last_attempt_at, processed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSED',
                        'WORKFLOW_INSTANCE', ?, 1, ?, ?, ?)
                """, uuid(message.tenantId()), uuid(inboxId), message.producerService(),
                uuid(message.messageId()), message.businessIntentKey(), message.contractRef(),
                message.contractVersion(), message.bodyBytes(), message.bodySha256(),
                uuid(message.correlationId()), nullableUuid(message.causationId()),
                Timestamp.from(message.occurredAt()), uuid(workflowInstanceId),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    void insertRejectedInbox(AcceptedWorkflowStart message,
                              UUID inboxId,
                              String errorCode,
                              Instant now) {
        insertRejectedInbox(message, inboxId, errorCode, 1, now);
    }

    void insertRejectedInbox(AcceptedWorkflowStart message,
                              UUID inboxId,
                              String errorCode,
                              int attemptCount,
                              Instant now) {
        jdbc.update("""
                INSERT INTO inbox_message
                  (tenant_id, inbox_id, producer_service, message_id, business_intent_key,
                   contract_ref, contract_version, body_bytes, body_sha256, correlation_id,
                   causation_id, occurred_at, status, attempt_count, last_error_code,
                   first_received_at, last_attempt_at, processed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REJECTED', ?, ?, ?, ?, ?)
                """, uuid(message.tenantId()), uuid(inboxId), message.producerService(),
                uuid(message.messageId()), message.businessIntentKey(), message.contractRef(),
                message.contractVersion(), message.bodyBytes(), message.bodySha256(),
                uuid(message.correlationId()), nullableUuid(message.causationId()),
                Timestamp.from(message.occurredAt()), attemptCount, errorCode,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    void insertRecoveryRequiredInbox(AcceptedWorkflowStart message,
                                     UUID inboxId,
                                     String errorCode,
                                     Instant now) {
        jdbc.update("""
                INSERT INTO inbox_message
                  (tenant_id, inbox_id, producer_service, message_id, business_intent_key,
                   contract_ref, contract_version, body_bytes, body_sha256, correlation_id,
                   causation_id, occurred_at, status, attempt_count, last_error_code,
                   first_received_at, last_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECOVERY_REQUIRED', ?, ?, ?, ?)
                """, uuid(message.tenantId()), uuid(inboxId), message.producerService(),
                uuid(message.messageId()), message.businessIntentKey(), message.contractRef(),
                message.contractVersion(), message.bodyBytes(), message.bodySha256(),
                uuid(message.correlationId()), nullableUuid(message.causationId()),
                Timestamp.from(message.occurredAt()), WorkflowStartContract.MAIN_DELIVERY_ATTEMPTS,
                errorCode, Timestamp.from(now), Timestamp.from(now));
    }

    RecoveryRecord findInboxRecovery(UUID tenantId, UUID inboxId, boolean lock) {
        List<RecoveryRecord> rows = jdbc.query("""
                SELECT recovery_id, state, lock_version, last_replay_correlation_id
                FROM message_recovery
                WHERE tenant_id = ? AND direction = 'INBOX' AND source_record_id = ?
                """ + forUpdate(lock), (rs, row) -> new RecoveryRecord(
                        uuid(rs.getBytes("recovery_id")),
                        rs.getString("state"),
                        rs.getLong("lock_version"),
                        nullableUuid(rs.getBytes("last_replay_correlation_id"))),
                uuid(tenantId), uuid(inboxId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    void insertInboxRecovery(AcceptedWorkflowStart message,
                             UUID inboxId,
                             UUID recoveryId,
                             String errorCode,
                             String errorSummary,
                             Instant now) {
        jdbc.update("""
                INSERT INTO message_recovery
                  (tenant_id, recovery_id, direction, source_record_id,
                   business_intent_key, state, attempt_count, last_error_code,
                   last_error_summary, dead_lettered_at, recovery_required_at)
                VALUES (?, ?, 'INBOX', ?, ?, 'OPEN', ?, ?, ?, ?, ?)
                """, uuid(message.tenantId()), uuid(recoveryId), uuid(inboxId),
                message.businessIntentKey(), WorkflowStartContract.MAIN_DELIVERY_ATTEMPTS,
                errorCode, errorSummary, Timestamp.from(now), Timestamp.from(now));
    }

    void completeRecoveryInbox(AcceptedWorkflowStart message,
                               InboxRecord inbox,
                               UUID workflowInstanceId,
                               Instant now) {
        int changed = jdbc.update("""
                UPDATE inbox_message
                SET status = 'PROCESSED', result_type = 'WORKFLOW_INSTANCE', result_id = ?,
                    attempt_count = attempt_count + 1, last_error_code = NULL,
                    last_attempt_at = ?, processed_at = ?, lock_version = lock_version + 1,
                    updated_at = ?, updated_by_type = 'SERVICE', updated_by_id = NULL
                WHERE tenant_id = ? AND inbox_id = ? AND status = 'RECOVERY_REQUIRED'
                  AND producer_service = ? AND message_id = ? AND contract_ref = ?
                  AND contract_version = ? AND business_intent_key = ? AND body_sha256 = ?
                  AND lock_version = ?
                """, uuid(workflowInstanceId), Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now), uuid(message.tenantId()), uuid(inbox.inboxId()),
                message.producerService(), uuid(message.messageId()), message.contractRef(),
                message.contractVersion(), message.businessIntentKey(), message.bodySha256(),
                inbox.lockVersion());
        requireSingleRecoveryChange(changed, "Inbox processing");
        resolveInboxRecovery(message.tenantId(), inbox.inboxId(), now);
    }

    void rejectRecoveryInbox(AcceptedWorkflowStart message,
                             InboxRecord inbox,
                             String errorCode,
                             Instant now) {
        int changed = jdbc.update("""
                UPDATE inbox_message
                SET status = 'REJECTED', attempt_count = attempt_count + 1,
                    last_error_code = ?, last_attempt_at = ?, processed_at = ?,
                    lock_version = lock_version + 1, updated_at = ?,
                    updated_by_type = 'SERVICE', updated_by_id = NULL
                WHERE tenant_id = ? AND inbox_id = ? AND status = 'RECOVERY_REQUIRED'
                  AND producer_service = ? AND message_id = ? AND contract_ref = ?
                  AND contract_version = ? AND business_intent_key = ? AND body_sha256 = ?
                  AND lock_version = ?
                """, errorCode, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                uuid(message.tenantId()), uuid(inbox.inboxId()), message.producerService(),
                uuid(message.messageId()), message.contractRef(), message.contractVersion(),
                message.businessIntentKey(), message.bodySha256(), inbox.lockVersion());
        requireSingleRecoveryChange(changed, "Inbox rejection");
        resolveInboxRecovery(message.tenantId(), inbox.inboxId(), now);
    }

    private void resolveInboxRecovery(UUID tenantId, UUID inboxId, Instant now) {
        RecoveryRecord recovery = findInboxRecovery(tenantId, inboxId, true);
        if (recovery == null) {
            throw new ConcurrencyFailureException(
                    "RECOVERY_REQUIRED Inbox has no matching INBOX Recovery");
        }
        if ("RESOLVED".equals(recovery.state())) {
            return;
        }
        int changed = jdbc.update("""
                UPDATE message_recovery
                SET state = 'RESOLVED', next_attempt_at = NULL, resolved_at = ?,
                    lock_version = lock_version + 1, updated_at = ?,
                    updated_by_type = 'SERVICE', updated_by_id = NULL
                WHERE tenant_id = ? AND recovery_id = ? AND direction = 'INBOX'
                  AND source_record_id = ? AND state IN ('OPEN', 'REPLAYING')
                  AND lock_version = ?
                """, Timestamp.from(now), Timestamp.from(now), uuid(tenantId),
                uuid(recovery.recoveryId()), uuid(inboxId), recovery.lockVersion());
        requireSingleRecoveryChange(changed, "Recovery resolution");
    }

    private static void requireSingleRecoveryChange(int changed, String operation) {
        if (changed != 1) {
            throw new ConcurrencyFailureException(operation + " lost its conditional update");
        }
    }

    void insertInstance(AcceptedWorkflowStart message,
                        UUID workflowInstanceId,
                        byte[] inputSnapshotJson,
                        byte[] inputSnapshotSha256,
                        boolean conditionResult,
                        Instant now) {
        String status = conditionResult ? "RUNNING" : "COMPLETED_PASSED";
        Integer currentLevel = conditionResult ? 1 : null;
        Timestamp completedAt = conditionResult ? null : Timestamp.from(now);
        jdbc.update("""
                INSERT INTO workflow_instance
                  (tenant_id, workflow_instance_id, ticket_id, start_intent_key,
                   definition_id, workflow_version_id, workflow_version_no,
                   input_snapshot_json, input_snapshot_sha256, condition_result,
                   status, current_level, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, uuid(message.tenantId()), uuid(workflowInstanceId), uuid(message.ticketId()),
                message.businessIntentKey(), uuid(message.workflowDefinitionId()),
                uuid(message.workflowVersionId()), message.versionNo(),
                new String(inputSnapshotJson, java.nio.charset.StandardCharsets.UTF_8),
                inputSnapshotSha256, conditionResult, status, currentLevel,
                Timestamp.from(now), completedAt);
    }

    void insertFirstLevelTask(UUID tenantId,
                              UUID taskId,
                              UUID workflowInstanceId,
                              UUID approverUserId) {
        jdbc.update("""
                INSERT INTO approval_task
                  (tenant_id, approval_task_id, workflow_instance_id, level_no,
                   approver_user_id, status)
                VALUES (?, ?, ?, 1, ?, 'PENDING')
                """, uuid(tenantId), uuid(taskId), uuid(workflowInstanceId),
                uuid(approverUserId));
    }

    void insertInstanceEstablishedOutbox(AcceptedWorkflowStart message,
                                         UUID workflowInstanceId,
                                         String instanceStatus,
                                         Instant startedAt) {
        byte[] body = WorkflowReverseEventCodec.instanceEstablished(
                message.ticketId(), workflowInstanceId, message.businessIntentKey(),
                message.workflowDefinitionId(), message.workflowVersionId(),
                message.versionNo(), instanceStatus, startedAt);
        insertOutbox(message.tenantId(), workflowInstanceId,
                WorkflowReverseEventContract.InstanceEstablished.CONTRACT_REF,
                WorkflowReverseEventCodec.instanceEstablishedIntent(
                        message.tenantId(), message.ticketId()), body,
                message.correlationId(), message.messageId(), startedAt);
    }

    void insertApprovalFinalizedOutbox(AcceptedWorkflowStart message,
                                       UUID workflowInstanceId,
                                       String outcome,
                                       Instant completedAt) {
        byte[] businessIntentKey = WorkflowReverseEventCodec.approvalFinalizedIntent(
                message.tenantId(), workflowInstanceId);
        byte[] body = WorkflowReverseEventCodec.approvalFinalized(
                message.ticketId(), workflowInstanceId, message.businessIntentKey(),
                message.workflowDefinitionId(), message.workflowVersionId(),
                message.versionNo(), outcome, completedAt, false);
        insertOutbox(message.tenantId(), workflowInstanceId,
                WorkflowReverseEventContract.ApprovalFinalized.CONTRACT_REF,
                businessIntentKey, body,
                message.correlationId(), message.messageId(), completedAt);
    }

    /** Inserts a Workflow-owned Notification request in the same start transaction. */
    void insertNotificationOutbox(UUID tenantId, UUID outboxId, UUID messageId,
                                  byte[] publicationKey, byte[] businessIntentKey,
                                  UUID taskId, int contractVersion, byte[] bodyBytes,
                                  byte[] bodySha256, UUID correlationId,
                                  UUID causationId, Instant now) {
        jdbc.update("""
                INSERT INTO outbox_event
                  (tenant_id, outbox_id, message_id, publication_key, business_intent_key,
                   aggregate_type, aggregate_id, contract_ref, contract_version, body_bytes,
                   body_sha256, correlation_id, causation_id, status, attempt_count,
                   next_attempt_at, lock_version, created_at, created_by_type, created_by_id,
                   updated_at, updated_by_type, updated_by_id)
                VALUES (?, ?, ?, ?, ?, 'NOTIFICATION_REQUEST', ?, ?, ?, ?, ?, ?, ?,
                        'PENDING', 0, ?, 0, ?, 'SERVICE', NULL, ?, 'SERVICE', NULL)
                """, uuid(tenantId), uuid(outboxId), uuid(messageId), publicationKey,
                businessIntentKey, uuid(taskId),
                com.flowdesk.common.notification.NotificationEventContract.WORKFLOW.contractRef(),
                contractVersion, bodyBytes, bodySha256, uuid(correlationId), nullableUuid(causationId),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    private void insertOutbox(UUID tenantId, UUID workflowInstanceId,
                              String contractRef, byte[] businessIntentKey,
                              byte[] body, UUID correlationId, UUID causationId,
                              Instant now) {
        UUID outboxId = WorkflowReverseEventCodec.outboxId(tenantId, contractRef, businessIntentKey);
        UUID messageId = WorkflowReverseEventCodec.messageId(tenantId, contractRef, businessIntentKey);
        jdbc.update("""
                INSERT INTO outbox_event
                  (tenant_id, outbox_id, message_id, publication_key,
                   business_intent_key, aggregate_type, aggregate_id,
                   contract_ref, contract_version, body_bytes, body_sha256,
                   correlation_id, causation_id, status, attempt_count,
                   next_attempt_at, lock_version, created_at, created_by_type,
                   created_by_id, updated_at, updated_by_type, updated_by_id)
                VALUES (?, ?, ?, ?, ?, 'WORKFLOW_INSTANCE', ?, ?, 1, ?, ?, ?, ?,
                        'PENDING', 0, ?, 0, ?, 'SERVICE', NULL, ?, 'SERVICE', NULL)
                """, uuid(tenantId), uuid(outboxId), uuid(messageId),
                WorkflowIdempotencyCodec.publicationKey(tenantId, messageId),
                businessIntentKey, uuid(workflowInstanceId), contractRef, body,
                WorkflowIdempotencyCodec.sha256(body), uuid(correlationId),
                nullableUuid(causationId), Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now));
    }

    void insertAudit(AcceptedWorkflowStart message,
                     UUID auditId,
                     String actionCode,
                     String targetType,
                     UUID targetId,
                     String outcome,
                     String reason,
                     String errorCode,
                     Instant now) {
        jdbc.update("""
                INSERT INTO audit_record
                  (tenant_id, audit_id, action_code, target_type, target_id, outcome,
                   actor_type, actor_id, reason, error_code, correlation_id,
                   causation_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, 'SERVICE', NULL, ?, ?, ?, ?, ?)
                """, uuid(message.tenantId()), uuid(auditId), actionCode, targetType,
                nullableUuid(targetId), outcome, reason, errorCode,
                uuid(message.correlationId()), nullableUuid(message.causationId()),
                Timestamp.from(now));
    }

    private List<InstanceRecord> findInstances(String field,
                                               byte[] value,
                                               UUID tenantId,
                                               boolean lock) {
        String sql = """
                SELECT workflow_instance_id, ticket_id, start_intent_key, definition_id,
                       workflow_version_id, workflow_version_no, input_snapshot_sha256,
                       condition_result
                FROM workflow_instance
                WHERE tenant_id = ? AND %s = ?
                """.formatted(field) + forUpdate(lock);
        return jdbc.query(sql, WorkflowStartRepository::mapInstance, uuid(tenantId), value);
    }

    private static boolean sameContent(InboxRecord existing, AcceptedWorkflowStart incoming) {
        return existing.contractRef().equals(incoming.contractRef())
                && existing.contractVersion() == incoming.contractVersion()
                && MessageDigest.isEqual(existing.businessIntentKey(), incoming.businessIntentKey())
                && MessageDigest.isEqual(existing.bodySha256(), incoming.bodySha256());
    }

    private static boolean instanceMatches(InstanceRecord existing,
                                           AcceptedWorkflowStart incoming,
                                           byte[] inputSnapshotSha256,
                                           boolean conditionResult) {
        return existing.ticketId().equals(incoming.ticketId())
                && MessageDigest.isEqual(
                existing.startIntentKey(), incoming.businessIntentKey())
                && existing.definitionId().equals(incoming.workflowDefinitionId())
                && existing.workflowVersionId().equals(incoming.workflowVersionId())
                && existing.workflowVersionNo() == incoming.versionNo()
                && MessageDigest.isEqual(existing.inputSnapshotSha256(), inputSnapshotSha256)
                && existing.conditionResult() == conditionResult;
    }

    private static InboxRecord mapInbox(ResultSet rs, int row) throws SQLException {
        return new InboxRecord(
                uuid(rs.getBytes("inbox_id")),
                uuid(rs.getBytes("message_id")),
                rs.getBytes("business_intent_key"),
                rs.getString("contract_ref"),
                rs.getInt("contract_version"),
                rs.getBytes("body_sha256"),
                rs.getString("status"),
                rs.getString("result_type"),
                nullableUuid(rs.getBytes("result_id")),
                rs.getLong("lock_version"));
    }

    private static VersionSnapshot mapVersion(ResultSet rs, int row) throws SQLException {
        return new VersionSnapshot(
                uuid(rs.getBytes("workflow_version_id")),
                uuid(rs.getBytes("definition_id")),
                rs.getInt("version_no"),
                rs.getString("version_status"),
                rs.getString("definition_status"),
                rs.getString("condition_field_code"),
                rs.getString("condition_operator"),
                rs.getString("condition_value_type"),
                rs.getString("condition_value_text"),
                rs.getInt("approval_level_count"),
                uuid(rs.getBytes("level1_approver_user_id")),
                uuid(rs.getBytes("level2_approver_user_id")),
                rs.getBytes("snapshot_sha256"));
    }

    private static InstanceRecord mapInstance(ResultSet rs, int row) throws SQLException {
        return new InstanceRecord(
                uuid(rs.getBytes("workflow_instance_id")),
                uuid(rs.getBytes("ticket_id")),
                rs.getBytes("start_intent_key"),
                uuid(rs.getBytes("definition_id")),
                uuid(rs.getBytes("workflow_version_id")),
                rs.getInt("workflow_version_no"),
                rs.getBytes("input_snapshot_sha256"),
                rs.getBoolean("condition_result"));
    }

    private static String forUpdate(boolean lock) {
        return lock ? " FOR UPDATE" : "";
    }

    static byte[] uuid(UUID value) {
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

    record InboxRecord(UUID inboxId,
                       UUID messageId,
                       byte[] businessIntentKey,
                       String contractRef,
                       int contractVersion,
                       byte[] bodySha256,
                       String status,
                       String resultType,
                       UUID resultId,
                       long lockVersion) {

        InboxRecord {
            businessIntentKey = businessIntentKey.clone();
            bodySha256 = bodySha256.clone();
        }
    }

    record RecoveryRecord(UUID recoveryId,
                          String state,
                          long lockVersion,
                          UUID lastReplayCorrelationId) {
    }

    enum DuplicateKind {
        FRESH,
        SAME_MESSAGE,
        SAME_BUSINESS_INTENT,
        CONFLICT
    }

    record DuplicateCheck(DuplicateKind kind,
                          InboxRecord existing,
                          String conflictCode) {

        static DuplicateCheck fresh() {
            return new DuplicateCheck(DuplicateKind.FRESH, null, null);
        }

        static DuplicateCheck sameMessage(InboxRecord existing) {
            return new DuplicateCheck(DuplicateKind.SAME_MESSAGE, existing, null);
        }

        static DuplicateCheck sameIntent(InboxRecord existing) {
            return new DuplicateCheck(DuplicateKind.SAME_BUSINESS_INTENT, existing, null);
        }

        static DuplicateCheck conflict(String code, InboxRecord existing) {
            return new DuplicateCheck(DuplicateKind.CONFLICT, existing, code);
        }

        boolean exactDuplicate() {
            return kind == DuplicateKind.SAME_MESSAGE
                    || kind == DuplicateKind.SAME_BUSINESS_INTENT;
        }

        boolean terminalDuplicate() {
            return exactDuplicate() && !"RECOVERY_REQUIRED".equals(existing.status());
        }

        boolean recoverableDuplicate() {
            return exactDuplicate() && "RECOVERY_REQUIRED".equals(existing.status());
        }
    }

    record VersionLookup(VersionSnapshot version, String errorCode) {

        static VersionLookup valid(VersionSnapshot version) {
            return new VersionLookup(version, null);
        }

        static VersionLookup invalid(String errorCode) {
            return new VersionLookup(null, errorCode);
        }

        boolean valid() {
            return version != null;
        }
    }

    record VersionSnapshot(UUID workflowVersionId,
                           UUID definitionId,
                           int versionNo,
                           String versionStatus,
                           String definitionStatus,
                           String conditionFieldCode,
                           String conditionOperator,
                           String conditionValueType,
                           String conditionValueText,
                           int approvalLevelCount,
                           UUID level1ApproverUserId,
                           UUID level2ApproverUserId,
                           byte[] snapshotSha256) {

        VersionSnapshot {
            snapshotSha256 = snapshotSha256.clone();
        }

        @Override
        public byte[] snapshotSha256() {
            return snapshotSha256.clone();
        }

        boolean requiresApproval(AcceptedWorkflowStart message) {
            return conditionValueText.equals(message.serviceRiskLevel());
        }

        boolean sameImmutableSnapshot(VersionSnapshot other) {
            return workflowVersionId.equals(other.workflowVersionId)
                    && definitionId.equals(other.definitionId)
                    && versionNo == other.versionNo
                    && versionStatus.equals(other.versionStatus)
                    && definitionStatus.equals(other.definitionStatus)
                    && conditionFieldCode.equals(other.conditionFieldCode)
                    && conditionOperator.equals(other.conditionOperator)
                    && conditionValueType.equals(other.conditionValueType)
                    && conditionValueText.equals(other.conditionValueText)
                    && approvalLevelCount == other.approvalLevelCount
                    && level1ApproverUserId.equals(other.level1ApproverUserId)
                    && level2ApproverUserId.equals(other.level2ApproverUserId)
                    && MessageDigest.isEqual(snapshotSha256, other.snapshotSha256);
        }
    }

    record InstanceRecord(UUID workflowInstanceId,
                          UUID ticketId,
                          byte[] startIntentKey,
                          UUID definitionId,
                          UUID workflowVersionId,
                          int workflowVersionNo,
                          byte[] inputSnapshotSha256,
                          boolean conditionResult) {
    }

    enum ExistingInstanceKind {
        FRESH,
        SAME,
        CONFLICT
    }

    record ExistingInstanceDecision(ExistingInstanceKind kind, UUID workflowInstanceId) {

        static ExistingInstanceDecision fresh() {
            return new ExistingInstanceDecision(ExistingInstanceKind.FRESH, null);
        }

        static ExistingInstanceDecision same(UUID workflowInstanceId) {
            return new ExistingInstanceDecision(ExistingInstanceKind.SAME, workflowInstanceId);
        }

        static ExistingInstanceDecision conflict() {
            return new ExistingInstanceDecision(ExistingInstanceKind.CONFLICT, null);
        }
    }
}
