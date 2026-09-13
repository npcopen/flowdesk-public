package com.flowdesk.workflow.repository;

import com.flowdesk.workflow.service.WorkflowIdempotencyCodec;
import com.flowdesk.workflow.event.WorkflowReverseEventCodec;
import com.flowdesk.workflow.event.WorkflowReverseEventContract;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-first persistence for Workflow definition, draft and published-version APIs. */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkflowRepository {

    private final JdbcTemplate jdbc;

    public WorkflowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<DefinitionRow> findDefinitions(UUID tenantId, String sortColumn,
                                               boolean ascending, String cursorValue,
                                               UUID cursorId, int pageSize) {
        String column = definitionSortColumn(sortColumn);
        String comparison = ascending ? ">" : "<";
        StringBuilder sql = new StringBuilder("""
                SELECT definition_id, definition_code, definition_name, status,
                       lock_version, created_at, updated_at
                FROM workflow_definition
                WHERE tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(bytes(tenantId));
        if (cursorValue != null && cursorId != null) {
            sql.append(" AND (").append(column).append(" ").append(comparison)
                    .append(" ? OR (").append(column).append(" = ? AND definition_id ")
                    .append(comparison).append(" ?))");
            args.add(cursorValue);
            args.add(cursorValue);
            args.add(bytes(cursorId));
        }
        sql.append(" ORDER BY ").append(column).append(ascending ? " ASC" : " DESC")
                .append(", definition_id ").append(ascending ? "ASC" : "DESC")
                .append(" LIMIT ").append(pageSize + 1);
        return jdbc.query(sql.toString(), WorkflowRepository::mapDefinition, args.toArray());
    }

    public Optional<DefinitionRow> findDefinition(UUID tenantId, UUID definitionId) {
        return findDefinition(tenantId, definitionId, false);
    }

    public Optional<DefinitionRow> findDefinitionForUpdate(UUID tenantId, UUID definitionId) {
        return findDefinition(tenantId, definitionId, true);
    }

    private Optional<DefinitionRow> findDefinition(UUID tenantId, UUID definitionId, boolean lock) {
        return jdbc.query("""
                SELECT definition_id, definition_code, definition_name, status,
                       lock_version, created_at, updated_at
                FROM workflow_definition
                WHERE tenant_id = ? AND definition_id = ?
                """ + (lock ? " FOR UPDATE" : ""), WorkflowRepository::mapDefinition,
                bytes(tenantId), bytes(definitionId)).stream().findFirst();
    }

    public Optional<DraftRow> findDraft(UUID tenantId, UUID draftId) {
        return findDraft(tenantId, draftId, false);
    }

    public Optional<DraftRow> findDraftForUpdate(UUID tenantId, UUID draftId) {
        return findDraft(tenantId, draftId, true);
    }

    private Optional<DraftRow> findDraft(UUID tenantId, UUID draftId, boolean lock) {
        return jdbc.query(draftSelect() + " WHERE tenant_id = ? AND draft_id = ?"
                        + (lock ? " FOR UPDATE" : ""), WorkflowRepository::mapDraft,
                bytes(tenantId), bytes(draftId)).stream().findFirst();
    }

    public Optional<DraftRow> findCurrentDraft(UUID tenantId, UUID definitionId) {
        return jdbc.query(draftSelect() + """
                 WHERE tenant_id = ? AND definition_id = ? AND status = 'EDITABLE'
                """, WorkflowRepository::mapDraft, bytes(tenantId), bytes(definitionId))
                .stream().findFirst();
    }

    public void insertDraft(UUID tenantId, UUID draftId, UUID definitionId,
                            String fieldCode, String operator, String valueType, String value,
                            UUID level1ApproverUserId, UUID level2ApproverUserId,
                            UUID actorId, Instant now) {
        jdbc.update("""
                INSERT INTO workflow_draft
                  (tenant_id, draft_id, definition_id, status,
                   condition_field_code, condition_operator, condition_value_type,
                   condition_value_text, approval_level_count,
                   level1_approver_user_id, level2_approver_user_id,
                   validation_state, lock_version, created_at, created_by_type,
                   created_by_id, updated_at, updated_by_type, updated_by_id)
                VALUES (?, ?, ?, 'EDITABLE', ?, ?, ?, ?, 2, ?, ?, 'UNVALIDATED', 0,
                        ?, 'USER', ?, ?, 'USER', ?)
                """, bytes(tenantId), bytes(draftId), bytes(definitionId), fieldCode,
                operator, valueType, value, bytes(level1ApproverUserId),
                bytes(level2ApproverUserId), Timestamp.from(now), bytes(actorId),
                Timestamp.from(now), bytes(actorId));
    }

    public int updateDraft(UUID tenantId, UUID draftId, long expectedVersion,
                           String fieldCode, String operator, String valueType, String value,
                           UUID level1ApproverUserId, UUID level2ApproverUserId,
                           UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE workflow_draft
                SET condition_field_code = ?, condition_operator = ?,
                    condition_value_type = ?, condition_value_text = ?,
                    level1_approver_user_id = ?, level2_approver_user_id = ?,
                    validation_state = 'UNVALIDATED', validation_digest = NULL,
                    validated_at = NULL, lock_version = lock_version + 1,
                    updated_at = ?, updated_by_type = 'USER', updated_by_id = ?
                WHERE tenant_id = ? AND draft_id = ? AND status = 'EDITABLE'
                  AND lock_version = ?
                """, fieldCode, operator, valueType, value, bytes(level1ApproverUserId),
                bytes(level2ApproverUserId), Timestamp.from(now), bytes(actorId),
                bytes(tenantId), bytes(draftId), expectedVersion);
    }

    public int markDraftValidated(UUID tenantId, UUID draftId, long expectedVersion,
                                  byte[] digest, UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE workflow_draft
                SET validation_state = 'VALID', validation_digest = ?, validated_at = ?,
                    lock_version = lock_version + 1, updated_at = ?,
                    updated_by_type = 'USER', updated_by_id = ?
                WHERE tenant_id = ? AND draft_id = ? AND status = 'EDITABLE'
                  AND lock_version = ?
                """, digest, Timestamp.from(now), Timestamp.from(now), bytes(actorId),
                bytes(tenantId), bytes(draftId), expectedVersion);
    }

    public int keepDraftUnvalidated(UUID tenantId, UUID draftId, long expectedVersion,
                                    UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE workflow_draft
                SET validation_state = 'UNVALIDATED', validation_digest = NULL,
                    validated_at = NULL, lock_version = lock_version + 1,
                    updated_at = ?, updated_by_type = 'USER', updated_by_id = ?
                WHERE tenant_id = ? AND draft_id = ? AND status = 'EDITABLE'
                  AND lock_version = ?
                """, Timestamp.from(now), bytes(actorId), bytes(tenantId), bytes(draftId),
                expectedVersion);
    }

    public int consumeDraft(UUID tenantId, UUID draftId, long expectedVersion,
                            UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE workflow_draft
                SET status = 'CONSUMED', consumed_at = ?, lock_version = lock_version + 1,
                    updated_at = ?, updated_by_type = 'USER', updated_by_id = ?
                WHERE tenant_id = ? AND draft_id = ? AND status = 'EDITABLE'
                  AND validation_state = 'VALID' AND lock_version = ?
                """, Timestamp.from(now), Timestamp.from(now), bytes(actorId),
                bytes(tenantId), bytes(draftId), expectedVersion);
    }

    public int nextVersionNo(UUID tenantId, UUID definitionId) {
        Integer current = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_no), 0)
                FROM workflow_version
                WHERE tenant_id = ? AND definition_id = ?
                """, Integer.class, bytes(tenantId), bytes(definitionId));
        return (current == null ? 0 : current) + 1;
    }

    public void insertVersion(UUID tenantId, UUID workflowVersionId, DraftRow draft,
                              int versionNo, UUID actorId, Instant now) {
        jdbc.update("""
                INSERT INTO workflow_version
                  (tenant_id, workflow_version_id, definition_id, version_no,
                   source_draft_id, status, condition_field_code, condition_operator,
                   condition_value_type, condition_value_text, approval_level_count,
                   level1_approver_user_id, level2_approver_user_id, snapshot_sha256,
                   published_at, published_by_type, published_by_id, created_at)
                VALUES (?, ?, ?, ?, ?, 'PUBLISHED', ?, ?, ?, ?, 2, ?, ?, ?, ?,
                        'USER', ?, ?)
                """, bytes(tenantId), bytes(workflowVersionId), bytes(draft.definitionId()),
                versionNo, bytes(draft.draftId()), draft.conditionFieldCode(),
                draft.conditionOperator(), draft.conditionValueType(),
                draft.conditionValueText(), bytes(draft.level1ApproverUserId()),
                bytes(draft.level2ApproverUserId()), draft.validationDigest(),
                Timestamp.from(now), bytes(actorId), Timestamp.from(now));
    }

    public Optional<VersionRow> findVersion(UUID tenantId, UUID workflowVersionId) {
        return jdbc.query(versionSelect() + " WHERE tenant_id = ? AND workflow_version_id = ?",
                WorkflowRepository::mapVersion, bytes(tenantId), bytes(workflowVersionId))
                .stream().findFirst();
    }

    public Optional<VersionRow> findVersionBySourceDraft(UUID tenantId, UUID draftId) {
        return jdbc.query(versionSelect() + " WHERE tenant_id = ? AND source_draft_id = ?",
                WorkflowRepository::mapVersion, bytes(tenantId), bytes(draftId))
                .stream().findFirst();
    }

    public List<VersionRow> findVersions(UUID tenantId, UUID definitionId, Integer versionNo,
                                         String sort, String cursorValue, UUID cursorId,
                                         int pageSize) {
        boolean ascending = !sort.startsWith("-");
        String column = sort.endsWith("publishedAt") ? "published_at" : "version_no";
        String comparison = ascending ? ">" : "<";
        StringBuilder sql = new StringBuilder(versionSelect())
                .append(" WHERE tenant_id = ? AND definition_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(bytes(tenantId));
        args.add(bytes(definitionId));
        if (versionNo != null) {
            sql.append(" AND version_no = ?");
            args.add(versionNo);
        }
        if (cursorValue != null && cursorId != null) {
            Object cursor = "version_no".equals(column)
                    ? Integer.parseInt(cursorValue) : Timestamp.from(Instant.parse(cursorValue));
            sql.append(" AND (").append(column).append(" ").append(comparison)
                    .append(" ? OR (").append(column).append(" = ? AND workflow_version_id ")
                    .append(comparison).append(" ?))");
            args.add(cursor);
            args.add(cursor);
            args.add(bytes(cursorId));
        }
        sql.append(" ORDER BY ").append(column).append(ascending ? " ASC" : " DESC")
                .append(", workflow_version_id ").append(ascending ? "ASC" : "DESC")
                .append(" LIMIT ").append(pageSize + 1);
        return jdbc.query(sql.toString(), WorkflowRepository::mapVersion, args.toArray());
    }

    public Optional<AuditRow> findAudit(UUID tenantId, UUID auditId) {
        return jdbc.query("""
                SELECT audit_id, action_code, target_type, target_id, outcome,
                       actor_type, actor_id, reason, error_code, correlation_id,
                       causation_id, occurred_at
                FROM audit_record
                WHERE tenant_id = ? AND audit_id = ?
                """, WorkflowRepository::mapAudit, bytes(tenantId), bytes(auditId))
                .stream().findFirst();
    }

    // ----- Workflow runtime / approval -----------------------------------------

    public List<InstanceRow> findInstances(UUID tenantId, String status, UUID ticketId,
                                           UUID workflowVersionId, UUID approverUserId,
                                           boolean ascending, Instant cursorStartedAt,
                                           UUID cursorId, int pageSize) {
        String comparison = ascending ? ">" : "<";
        StringBuilder sql = new StringBuilder(instanceSelect())
                .append(" WHERE i.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(bytes(tenantId));
        if (status != null) {
            sql.append(" AND i.status = ?");
            args.add(status);
        }
        if (ticketId != null) {
            sql.append(" AND i.ticket_id = ?");
            args.add(bytes(ticketId));
        }
        if (workflowVersionId != null) {
            sql.append(" AND i.workflow_version_id = ?");
            args.add(bytes(workflowVersionId));
        }
        if (approverUserId != null) {
            sql.append("""
                     AND EXISTS (
                       SELECT 1 FROM approval_task scope_task
                       WHERE scope_task.tenant_id = i.tenant_id
                         AND scope_task.workflow_instance_id = i.workflow_instance_id
                         AND scope_task.approver_user_id = ?)
                    """);
            args.add(bytes(approverUserId));
        }
        if (cursorStartedAt != null && cursorId != null) {
            sql.append(" AND (i.started_at ").append(comparison)
                    .append(" ? OR (i.started_at = ? AND i.workflow_instance_id ")
                    .append(comparison).append(" ?))");
            args.add(Timestamp.from(cursorStartedAt));
            args.add(Timestamp.from(cursorStartedAt));
            args.add(bytes(cursorId));
        }
        sql.append(" ORDER BY i.started_at ").append(ascending ? "ASC" : "DESC")
                .append(", i.workflow_instance_id ").append(ascending ? "ASC" : "DESC")
                .append(" LIMIT ").append(pageSize + 1);
        return jdbc.query(sql.toString(), WorkflowRepository::mapInstance, args.toArray());
    }

    public Optional<InstanceRow> findInstance(UUID tenantId, UUID workflowInstanceId,
                                              UUID approverUserId, boolean lock) {
        StringBuilder sql = new StringBuilder(instanceSelect())
                .append(" WHERE i.tenant_id = ? AND i.workflow_instance_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(bytes(tenantId));
        args.add(bytes(workflowInstanceId));
        if (approverUserId != null) {
            sql.append("""
                     AND EXISTS (
                       SELECT 1 FROM approval_task scope_task
                       WHERE scope_task.tenant_id = i.tenant_id
                         AND scope_task.workflow_instance_id = i.workflow_instance_id
                         AND scope_task.approver_user_id = ?)
                    """);
            args.add(bytes(approverUserId));
        }
        if (lock) {
            sql.append(" FOR UPDATE");
        }
        return jdbc.query(sql.toString(), WorkflowRepository::mapInstance, args.toArray())
                .stream().findFirst();
    }

    public List<TaskRow> findTasks(UUID tenantId, String status, Integer levelNo,
                                   UUID workflowInstanceId, UUID approverUserId,
                                   String sort, String cursorValue, UUID cursorId,
                                   int pageSize) {
        boolean ascending = !sort.startsWith("-");
        String column = switch (sort.replace("-", "")) {
            case "createdAt" -> "created_at";
            case "decidedAt" -> "COALESCE(decided_at, created_at)";
            default -> throw new IllegalArgumentException("Unsupported task sort");
        };
        String comparison = ascending ? ">" : "<";
        StringBuilder sql = new StringBuilder(taskSelect()).append(" WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(bytes(tenantId));
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (levelNo != null) {
            sql.append(" AND level_no = ?");
            args.add(levelNo);
        }
        if (workflowInstanceId != null) {
            sql.append(" AND workflow_instance_id = ?");
            args.add(bytes(workflowInstanceId));
        }
        if (approverUserId != null) {
            sql.append(" AND approver_user_id = ?");
            args.add(bytes(approverUserId));
        }
        if (cursorValue != null && cursorId != null) {
            Timestamp cursor = Timestamp.from(Instant.parse(cursorValue));
            sql.append(" AND (").append(column).append(" ").append(comparison)
                    .append(" ? OR (").append(column).append(" = ? AND approval_task_id ")
                    .append(comparison).append(" ?))");
            args.add(cursor);
            args.add(cursor);
            args.add(bytes(cursorId));
        }
        sql.append(" ORDER BY ").append(column).append(ascending ? " ASC" : " DESC")
                .append(", approval_task_id ").append(ascending ? "ASC" : "DESC")
                .append(" LIMIT ").append(pageSize + 1);
        return jdbc.query(sql.toString(), WorkflowRepository::mapTask, args.toArray());
    }

    public long countPendingTasks(UUID tenantId, UUID approverUserId) {
        String sql = "SELECT COUNT(*) FROM approval_task WHERE tenant_id = ? AND status = 'PENDING'";
        return approverUserId == null
                ? jdbc.queryForObject(sql, Long.class, bytes(tenantId))
                : jdbc.queryForObject(sql + " AND approver_user_id = ?", Long.class,
                        bytes(tenantId), bytes(approverUserId));
    }

    public Optional<TaskRow> findTask(UUID tenantId, UUID taskId, boolean lock) {
        return jdbc.query(taskSelect() + " WHERE tenant_id = ? AND approval_task_id = ?"
                        + (lock ? " FOR UPDATE" : ""), WorkflowRepository::mapTask,
                bytes(tenantId), bytes(taskId)).stream().findFirst();
    }

    public Optional<UUID> findLevel2Approver(UUID tenantId, UUID workflowInstanceId) {
        return jdbc.query("""
                SELECT COALESCE(o.approver_user_id, v.level2_approver_user_id)
                FROM workflow_instance i
                JOIN workflow_version v
                  ON v.tenant_id = i.tenant_id
                 AND v.workflow_version_id = i.workflow_version_id
                 AND v.definition_id = i.definition_id
                 AND v.version_no = i.workflow_version_no
                LEFT JOIN workflow_approver_override o
                  ON o.tenant_id=i.tenant_id AND o.workflow_instance_id=i.workflow_instance_id AND o.level_no=2
                WHERE i.tenant_id = ? AND i.workflow_instance_id = ?
                """, (rs, row) -> uuid(rs.getBytes(1)), bytes(tenantId),
                bytes(workflowInstanceId)).stream().findFirst();
    }

    public int decideTask(UUID tenantId, UUID taskId, long expectedVersion,
                          String status, String decisionType, String reason,
                          byte[] decisionIntentKey, UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE approval_task
                SET status = ?, decision_type = ?, decision_reason = ?,
                    decision_intent_key = ?, decided_at = ?, decided_by_user_id = ?,
                    lock_version = lock_version + 1, updated_at = ?,
                    updated_by_type = 'USER', updated_by_id = ?
                WHERE tenant_id = ? AND approval_task_id = ? AND status = 'PENDING'
                  AND approver_user_id = ? AND lock_version = ?
                """, status, decisionType, reason, decisionIntentKey, Timestamp.from(now),
                bytes(actorId), Timestamp.from(now), bytes(actorId), bytes(tenantId),
                bytes(taskId), bytes(actorId), expectedVersion);
    }

    public Optional<TaskRow> findPendingTask(UUID tenantId, UUID instanceId) {
        return jdbc.query(taskSelect() + " WHERE tenant_id=? AND workflow_instance_id=? AND status='PENDING'",
                WorkflowRepository::mapTask, bytes(tenantId), bytes(instanceId)).stream().findFirst();
    }

    public Optional<UUID> findEffectiveApprover(UUID tenantId, UUID instanceId, int levelNo) {
        if (levelNo == 2) return findLevel2Approver(tenantId, instanceId);
        return jdbc.query("SELECT approver_user_id FROM approval_task WHERE tenant_id=? AND workflow_instance_id=? AND level_no=1",
                (rs, n) -> uuid(rs.getBytes(1)), bytes(tenantId), bytes(instanceId)).stream().findFirst();
    }

    public Optional<ApproverChangeRow> findApproverChange(UUID tenantId, UUID changeId) {
        return jdbc.query("SELECT * FROM workflow_approver_change WHERE tenant_id=? AND change_id=?",
                WorkflowRepository::mapApproverChange, bytes(tenantId), bytes(changeId)).stream().findFirst();
    }

    public List<ApproverChangeRow> findApproverChanges(UUID tenantId, UUID instanceId) {
        return jdbc.query("SELECT * FROM workflow_approver_change WHERE tenant_id=? AND workflow_instance_id=? ORDER BY changed_at DESC,change_id DESC LIMIT 100",
                WorkflowRepository::mapApproverChange, bytes(tenantId), bytes(instanceId));
    }

    public void insertApproverChange(UUID tenantId, UUID changeId, UUID instanceId, int levelNo,
                                     UUID previous, UUID next, UUID actorId, String reason, String digest, Instant now) {
        jdbc.update("""
                INSERT INTO workflow_approver_change
                  (tenant_id,change_id,workflow_instance_id,level_no,previous_approver_user_id,
                   approver_user_id,changed_by_user_id,reason,request_digest,changed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, bytes(tenantId), bytes(changeId), bytes(instanceId), levelNo, bytes(previous), bytes(next),
                bytes(actorId), reason, digest, Timestamp.from(now));
    }

    public void overrideApprover(UUID tenantId, UUID instanceId, int levelNo, UUID next, Instant now) {
        if (jdbc.update("UPDATE workflow_approver_override SET approver_user_id=?,updated_at=? WHERE tenant_id=? AND workflow_instance_id=? AND level_no=?",
                bytes(next), Timestamp.from(now), bytes(tenantId), bytes(instanceId), levelNo) == 0) {
            jdbc.update("INSERT INTO workflow_approver_override (tenant_id,workflow_instance_id,level_no,approver_user_id,updated_at) VALUES (?,?,?,?,?)",
                    bytes(tenantId), bytes(instanceId), levelNo, bytes(next), Timestamp.from(now));
        }
    }

    public int reassignPendingTask(UUID tenantId, TaskRow task, UUID next, UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE approval_task SET approver_user_id=?,lock_version=lock_version+1,
                  updated_at=?,updated_by_type='USER',updated_by_id=?
                WHERE tenant_id=? AND approval_task_id=? AND status='PENDING' AND lock_version=?
                """, bytes(next), Timestamp.from(now), bytes(actorId), bytes(tenantId), bytes(task.approvalTaskId()), task.lockVersion());
    }

    public int touchInstanceForApproverChange(UUID tenantId, UUID instanceId, long expectedVersion, UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE workflow_instance SET lock_version=lock_version+1,updated_at=?,updated_by_type='USER',updated_by_id=?
                WHERE tenant_id=? AND workflow_instance_id=? AND status='RUNNING' AND lock_version=?
                """, Timestamp.from(now), bytes(actorId), bytes(tenantId), bytes(instanceId), expectedVersion);
    }

    private static ApproverChangeRow mapApproverChange(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new ApproverChangeRow(uuid(rs.getBytes("change_id")), uuid(rs.getBytes("workflow_instance_id")),
                rs.getInt("level_no"), uuid(rs.getBytes("previous_approver_user_id")), uuid(rs.getBytes("approver_user_id")),
                uuid(rs.getBytes("changed_by_user_id")), rs.getString("reason"), rs.getString("request_digest"),
                rs.getTimestamp("changed_at").toInstant());
    }

    public record ApproverChangeRow(UUID changeId, UUID instanceId, int levelNo, UUID previous,
                                     UUID next, UUID actorId, String reason, String digest, Instant changedAt) { }

    public int advanceInstanceToLevel2(UUID tenantId, UUID workflowInstanceId,
                                       long expectedVersion, UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE workflow_instance
                SET current_level = 2, lock_version = lock_version + 1,
                    updated_at = ?, updated_by_type = 'USER', updated_by_id = ?
                WHERE tenant_id = ? AND workflow_instance_id = ? AND status = 'RUNNING'
                  AND current_level = 1 AND lock_version = ?
                """, Timestamp.from(now), bytes(actorId), bytes(tenantId),
                bytes(workflowInstanceId), expectedVersion);
    }

    public void insertSecondLevelTask(UUID tenantId, UUID taskId, UUID workflowInstanceId,
                                      UUID approverUserId, Instant now) {
        jdbc.update("""
                INSERT INTO approval_task
                  (tenant_id, approval_task_id, workflow_instance_id, level_no,
                   approver_user_id, status, lock_version, created_at,
                   created_by_type, updated_at, updated_by_type)
                VALUES (?, ?, ?, 2, ?, 'PENDING', 0, ?, 'SERVICE', ?, 'SERVICE')
                """, bytes(tenantId), bytes(taskId), bytes(workflowInstanceId),
                bytes(approverUserId), Timestamp.from(now), Timestamp.from(now));
    }

    /** Inserts a Workflow-owned Notification request in the same approval transaction. */
    public void insertNotificationOutbox(UUID tenantId, UUID outboxId, UUID messageId,
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
                """, bytes(tenantId), bytes(outboxId), bytes(messageId), publicationKey,
                businessIntentKey, bytes(taskId),
                com.flowdesk.common.notification.NotificationEventContract.WORKFLOW.contractRef(),
                contractVersion, bodyBytes, bodySha256, bytes(correlationId), nullableBytes(causationId),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    public int completeInstance(UUID tenantId, UUID workflowInstanceId,
                                long expectedVersion, String status,
                                UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE workflow_instance
                SET status = ?, current_level = NULL, completed_at = ?,
                    lock_version = lock_version + 1, updated_at = ?,
                    updated_by_type = 'USER', updated_by_id = ?
                WHERE tenant_id = ? AND workflow_instance_id = ? AND status = 'RUNNING'
                  AND lock_version = ?
                """, status, Timestamp.from(now), Timestamp.from(now), bytes(actorId),
                bytes(tenantId), bytes(workflowInstanceId), expectedVersion);
    }

    public void insertApprovalFinalizedOutbox(UUID tenantId, InstanceRow instance,
                                              String outcome, UUID correlationId, UUID actorId,
                                              Instant completedAt, String decisionReason) {
        byte[] businessIntentKey = WorkflowReverseEventCodec.approvalFinalizedIntent(
                tenantId, instance.workflowInstanceId());
        byte[] body = WorkflowReverseEventCodec.approvalFinalized(instance.ticketId(),
                instance.workflowInstanceId(), instance.startIntentKey(),
                instance.definitionId(), instance.workflowVersionId(),
                instance.workflowVersionNo(), outcome, completedAt, null, decisionReason);
        UUID outboxId = WorkflowReverseEventCodec.outboxId(tenantId,
                WorkflowReverseEventContract.ApprovalFinalized.CONTRACT_REF, businessIntentKey);
        UUID messageId = WorkflowReverseEventCodec.messageId(tenantId,
                WorkflowReverseEventContract.ApprovalFinalized.CONTRACT_REF, businessIntentKey);
        jdbc.update("""
                INSERT INTO outbox_event
                  (tenant_id, outbox_id, message_id, publication_key,
                   business_intent_key, aggregate_type, aggregate_id,
                   contract_ref, contract_version, body_bytes, body_sha256,
                   correlation_id, causation_id, status, attempt_count,
                   next_attempt_at, lock_version, created_at, created_by_type,
                   created_by_id, updated_at, updated_by_type, updated_by_id)
                VALUES (?, ?, ?, ?, ?, 'WORKFLOW_INSTANCE', ?, ?, 1, ?, ?, ?, ?,
                        'PENDING', 0, ?, 0, ?, 'USER', ?, ?, 'USER', ?)
                """, bytes(tenantId), bytes(outboxId), bytes(messageId),
                WorkflowIdempotencyCodec.publicationKey(tenantId, messageId),
                businessIntentKey,
                bytes(instance.workflowInstanceId()),
                WorkflowReverseEventContract.ApprovalFinalized.CONTRACT_REF,
                body, WorkflowIdempotencyCodec.sha256(body), bytes(correlationId),
                bytes(instance.startMessageId()), Timestamp.from(completedAt),
                Timestamp.from(completedAt), bytes(actorId),
                Timestamp.from(completedAt), bytes(actorId));
    }

    public void insertAudit(UUID tenantId, UUID auditId, String actionCode,
                            String targetType, UUID targetId, String outcome,
                            UUID actorId, String reason, String errorCode,
                            UUID correlationId, UUID causationId, Instant now) {
        jdbc.update("""
                INSERT INTO audit_record
                  (tenant_id, audit_id, action_code, target_type, target_id, outcome,
                   actor_type, actor_id, reason, error_code, correlation_id,
                   causation_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, 'USER', ?, ?, ?, ?, ?, ?)
                """, bytes(tenantId), bytes(auditId), actionCode, targetType,
                nullableBytes(targetId), outcome, bytes(actorId), reason, errorCode,
                bytes(correlationId), nullableBytes(causationId), Timestamp.from(now));
    }

    private static String draftSelect() {
        return """
                SELECT draft_id, definition_id, status, condition_field_code,
                       condition_operator, condition_value_type, condition_value_text,
                       approval_level_count, level1_approver_user_id,
                       level2_approver_user_id, validation_state, validation_digest,
                       validated_at, consumed_at, lock_version, created_at, updated_at
                FROM workflow_draft
                """;
    }

    private static String versionSelect() {
        return """
                SELECT workflow_version_id, definition_id, version_no, source_draft_id,
                       status, condition_field_code, condition_operator,
                       condition_value_type, condition_value_text, approval_level_count,
                       level1_approver_user_id, level2_approver_user_id, snapshot_sha256,
                       published_at, published_by_id
                FROM workflow_version
                """;
    }

    private static String instanceSelect() {
        return """
                SELECT i.workflow_instance_id, i.ticket_id, i.start_intent_key,
                       i.definition_id, i.workflow_version_id, i.workflow_version_no,
                       i.input_snapshot_json, i.input_snapshot_sha256, i.condition_result,
                       i.status, i.current_level, i.started_at, i.completed_at,
                       i.lock_version, i.created_at, i.updated_at,
                       start_inbox.message_id AS start_message_id,
                       i.updated_by_id AS last_actor_id
                FROM workflow_instance i
                LEFT JOIN inbox_message start_inbox
                  ON start_inbox.tenant_id = i.tenant_id
                 AND start_inbox.result_type = 'WORKFLOW_INSTANCE'
                 AND start_inbox.result_id = i.workflow_instance_id
                """;
    }

    private static String taskSelect() {
        return """
                SELECT approval_task_id, workflow_instance_id, level_no,
                       approver_user_id, status, decision_type, decision_reason,
                       decision_intent_key, decided_at, decided_by_user_id,
                       lock_version, created_at, updated_at
                FROM approval_task
                """;
    }

    private static String definitionSortColumn(String value) {
        return switch (value) {
            case "definitionCode" -> "definition_code";
            case "definitionName" -> "definition_name";
            default -> throw new IllegalArgumentException("Unsupported definition sort");
        };
    }

    private static DefinitionRow mapDefinition(ResultSet rs, int row) throws SQLException {
        return new DefinitionRow(uuid(rs.getBytes("definition_id")),
                rs.getString("definition_code"), rs.getString("definition_name"),
                rs.getString("status"), rs.getLong("lock_version"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static DraftRow mapDraft(ResultSet rs, int row) throws SQLException {
        return new DraftRow(uuid(rs.getBytes("draft_id")), uuid(rs.getBytes("definition_id")),
                rs.getString("status"), rs.getString("condition_field_code"),
                rs.getString("condition_operator"), rs.getString("condition_value_type"),
                rs.getString("condition_value_text"), rs.getInt("approval_level_count"),
                uuid(rs.getBytes("level1_approver_user_id")),
                uuid(rs.getBytes("level2_approver_user_id")),
                rs.getString("validation_state"), rs.getBytes("validation_digest"),
                instant(rs, "validated_at"), instant(rs, "consumed_at"),
                rs.getLong("lock_version"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static VersionRow mapVersion(ResultSet rs, int row) throws SQLException {
        return new VersionRow(uuid(rs.getBytes("workflow_version_id")),
                uuid(rs.getBytes("definition_id")), rs.getInt("version_no"),
                uuid(rs.getBytes("source_draft_id")), rs.getString("status"),
                rs.getString("condition_field_code"), rs.getString("condition_operator"),
                rs.getString("condition_value_type"), rs.getString("condition_value_text"),
                rs.getInt("approval_level_count"),
                uuid(rs.getBytes("level1_approver_user_id")),
                uuid(rs.getBytes("level2_approver_user_id")),
                rs.getBytes("snapshot_sha256"), instant(rs, "published_at"),
                nullableUuid(rs.getBytes("published_by_id")));
    }

    private static AuditRow mapAudit(ResultSet rs, int row) throws SQLException {
        return new AuditRow(uuid(rs.getBytes("audit_id")), rs.getString("action_code"),
                rs.getString("target_type"), nullableUuid(rs.getBytes("target_id")),
                rs.getString("outcome"), rs.getString("actor_type"),
                nullableUuid(rs.getBytes("actor_id")), rs.getString("reason"),
                rs.getString("error_code"), uuid(rs.getBytes("correlation_id")),
                nullableUuid(rs.getBytes("causation_id")), instant(rs, "occurred_at"));
    }

    private static InstanceRow mapInstance(ResultSet rs, int row) throws SQLException {
        return new InstanceRow(uuid(rs.getBytes("workflow_instance_id")),
                uuid(rs.getBytes("ticket_id")), rs.getBytes("start_intent_key"),
                uuid(rs.getBytes("definition_id")), uuid(rs.getBytes("workflow_version_id")),
                rs.getInt("workflow_version_no"), rs.getString("input_snapshot_json"),
                rs.getBytes("input_snapshot_sha256"), rs.getBoolean("condition_result"),
                rs.getString("status"), (Integer) rs.getObject("current_level"),
                instant(rs, "started_at"), instant(rs, "completed_at"),
                rs.getLong("lock_version"), instant(rs, "created_at"),
                instant(rs, "updated_at"), nullableUuid(rs.getBytes("start_message_id")),
                nullableUuid(rs.getBytes("last_actor_id")));
    }

    private static TaskRow mapTask(ResultSet rs, int row) throws SQLException {
        return new TaskRow(uuid(rs.getBytes("approval_task_id")),
                uuid(rs.getBytes("workflow_instance_id")), rs.getInt("level_no"),
                uuid(rs.getBytes("approver_user_id")), rs.getString("status"),
                rs.getString("decision_type"), rs.getString("decision_reason"),
                rs.getBytes("decision_intent_key"), instant(rs, "decided_at"),
                nullableUuid(rs.getBytes("decided_by_user_id")),
                rs.getLong("lock_version"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static byte[] bytes(UUID value) {
        return WorkflowIdempotencyCodec.uuidBytes(value);
    }

    private static byte[] nullableBytes(UUID value) {
        return value == null ? null : bytes(value);
    }

    private static UUID uuid(byte[] value) {
        return WorkflowIdempotencyCodec.uuid(value);
    }

    private static UUID nullableUuid(byte[] value) {
        return WorkflowIdempotencyCodec.nullableUuid(value);
    }

    public record DefinitionRow(UUID definitionId, String definitionCode,
                                String definitionName, String status, long lockVersion,
                                Instant createdAt, Instant updatedAt) {
    }

    public record DraftRow(UUID draftId, UUID definitionId, String status,
                           String conditionFieldCode, String conditionOperator,
                           String conditionValueType, String conditionValueText,
                           int approvalLevelCount, UUID level1ApproverUserId,
                           UUID level2ApproverUserId, String validationState,
                           byte[] validationDigest, Instant validatedAt, Instant consumedAt,
                           long lockVersion, Instant createdAt, Instant updatedAt) {
        public DraftRow {
            validationDigest = validationDigest == null ? null : validationDigest.clone();
        }

        @Override
        public byte[] validationDigest() {
            return validationDigest == null ? null : validationDigest.clone();
        }
    }

    public record VersionRow(UUID workflowVersionId, UUID definitionId, int versionNo,
                             UUID sourceDraftId, String status, String conditionFieldCode,
                             String conditionOperator, String conditionValueType,
                             String conditionValueText, int approvalLevelCount,
                             UUID level1ApproverUserId, UUID level2ApproverUserId,
                             byte[] snapshotSha256, Instant publishedAt,
                             UUID publishedByUserId) {
        public VersionRow {
            snapshotSha256 = snapshotSha256.clone();
        }

        @Override
        public byte[] snapshotSha256() {
            return snapshotSha256.clone();
        }
    }

    public record AuditRow(UUID auditId, String actionCode, String targetType,
                           UUID targetId, String outcome, String actorType, UUID actorId,
                           String reason, String errorCode, UUID correlationId,
                           UUID causationId, Instant occurredAt) {
    }

    public record InstanceRow(UUID workflowInstanceId, UUID ticketId,
                              byte[] startIntentKey, UUID definitionId,
                              UUID workflowVersionId, int workflowVersionNo,
                              String inputSnapshotJson, byte[] inputSnapshotSha256,
                              boolean conditionResult, String status, Integer currentLevel,
                              Instant startedAt, Instant completedAt, long lockVersion,
                              Instant createdAt, Instant updatedAt, UUID startMessageId,
                              UUID lastActorId) {
        public InstanceRow {
            startIntentKey = startIntentKey.clone();
            inputSnapshotSha256 = inputSnapshotSha256.clone();
        }

        @Override public byte[] startIntentKey() { return startIntentKey.clone(); }
        @Override public byte[] inputSnapshotSha256() { return inputSnapshotSha256.clone(); }
    }

    public record TaskRow(UUID approvalTaskId, UUID workflowInstanceId, int levelNo,
                          UUID approverUserId, String status, String decisionType,
                          String decisionReason, byte[] decisionIntentKey,
                          Instant decidedAt, UUID decidedByUserId, long lockVersion,
                          Instant createdAt, Instant updatedAt) {
        public TaskRow {
            decisionIntentKey = decisionIntentKey == null ? null : decisionIntentKey.clone();
        }

        @Override public byte[] decisionIntentKey() {
            return decisionIntentKey == null ? null : decisionIntentKey.clone();
        }
    }
}
