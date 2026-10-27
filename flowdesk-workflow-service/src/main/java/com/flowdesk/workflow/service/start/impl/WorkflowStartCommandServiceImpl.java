package com.flowdesk.workflow.service.start.impl;

import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.tenant.TenantContextSource;
import com.flowdesk.workflow.notification.WorkflowNotificationIntent;
import com.flowdesk.workflow.notification.WorkflowNotificationOutboxWriter;
import com.flowdesk.workflow.service.WorkflowIdempotencyCodec;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.flowdesk.workflow.start.*;
import com.flowdesk.workflow.model.start.WorkflowStartModels.*;
import com.flowdesk.workflow.service.start.*;
import com.flowdesk.workflow.event.WorkflowReverseEventCodec;
import com.flowdesk.workflow.event.WorkflowReverseEventContract;
import org.springframework.dao.ConcurrencyFailureException;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.util.List;

/**
 * 工作流业务契约实现，协调声明的持久化与外部依赖，并在明确事务边界内维护业务事实一致性。
 */
public final class WorkflowStartCommandServiceImpl implements WorkflowStartCommandService {
    private static final String MESSAGE_CONFLICT = "MESSAGE_ID_CONTENT_CONFLICT";
    private static final String INTENT_CONFLICT = "BUSINESS_INTENT_CONTENT_CONFLICT";
    private static final String VERSION_REFERENCE_INVALID = "WORKFLOW_VERSION_REFERENCE_INVALID";
    private static final String VERSION_STATE_INVALID = "WORKFLOW_VERSION_STATE_INVALID";
    private static final String VERSION_RULE_INVALID = "WORKFLOW_VERSION_RULE_INVALID";
    private static final String INSTANCE_CONFLICT = "WORKFLOW_INSTANCE_CONTENT_CONFLICT";

    private static final String VERSION_CHANGED = "WORKFLOW_VERSION_CONTENT_CHANGED";

    private final WorkflowStartMapper mapper;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final WorkflowNotificationOutboxWriter notificationWriter;

    /**
     * 注入启动映射和事务管理器，在短事务保存 Inbox、实例、任务及消息副作用；可选时钟和通知写入器支持一致时间和原子通知。
     */
    public WorkflowStartCommandServiceImpl(WorkflowStartMapper mapper,
                             PlatformTransactionManager transactionManager) {
        this(mapper, transactionManager, Clock.systemUTC(), null);
    }

    /**
     * 注入启动映射和事务管理器，在短事务保存 Inbox、实例、任务及消息副作用；可选时钟和通知写入器支持一致时间和原子通知。
     */
    public WorkflowStartCommandServiceImpl(WorkflowStartMapper mapper,
                             PlatformTransactionManager transactionManager,
                             Clock clock,
                             WorkflowNotificationOutboxWriter notificationWriter) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.notificationWriter = notificationWriter;
    }

    /**
     * 在短本地事务完成启动决定，返回明确提交结果供入口决定是否确认消息。
     */
    public Completion complete(AcceptedWorkflowStart message, Preflight preflight) {
        Completion completion = transactions.execute(status -> completeInside(message, preflight));
        if (completion == null) {
            throw new IllegalStateException("Workflow-start transaction returned no decision");
        }
        return completion;
    }

    /**
     * 核对可信租户、重复事实及预检快照，原子保存实例、首级任务、消息意图和审计。
     */
    private Completion completeInside(AcceptedWorkflowStart message, Preflight preflight) {
        var context = TenantContextHolder.current();
        context.requireTenant(message.tenantId());
        if (context.source() != TenantContextSource.BROKER_MESSAGE) {
            throw new IllegalStateException("Workflow-start requires a Broker TenantContext");
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        DuplicateCheck duplicate =
                findDuplicate(message, true);
        if (duplicate.terminalDuplicate()) {
            return Completion.duplicate(duplicate.existing().resultId());
        }
        if (duplicate.kind() == DuplicateKind.CONFLICT) {
            appendConflictAudit(message, duplicate, now);
            return Completion.ack(duplicate.conflictCode(), null);
        }
        if (preflight.kind() == PreflightKind.CONFLICT_ONLY) {
            return Completion.needsPreflight();
        }

        // 资格预检在事务外完成；事务内再核对不可变版本快照，防止预检结果用于另一套规则。
        VersionLookup currentVersion = findVersion(message);
        if (preflight.version() != null) {
            if (!currentVersion.valid()) {
                return reject(message, duplicate, currentVersion.errorCode(), now);
            }
            if (!preflight.version().sameImmutableSnapshot(currentVersion.version())) {
                return reject(message, duplicate, VERSION_CHANGED, now);
            }
        }
        if (preflight.kind() == PreflightKind.REJECT) {
            return reject(message, duplicate, preflight.errorCode(), now);
        }
        if (!currentVersion.valid()) {
            return reject(message, duplicate, currentVersion.errorCode(), now);
        }

        VersionSnapshot version = currentVersion.version();
        boolean conditionResult = version.requiresApproval(message);
        byte[] inputSnapshotJson = inputSnapshotJson(message.serviceRiskLevel());
        byte[] inputSnapshotSha256 = sha256(inputSnapshotJson);
        if (conditionResult
                && !version.level1ApproverUserId().equals(preflight.eligibleApproverUserId())) {
            throw new IllegalStateException("The preflight did not authorize the current approver");
        }
        if (!conditionResult && preflight.eligibleApproverUserId() != null) {
            throw new IllegalStateException("An IAM preflight was attached to a no-task result");
        }

        // 同时核对既有实例的业务意图与输入快照，避免相同工单被悄悄绑定到不同流程内容。
        ExistingInstanceDecision existing =
                findExistingInstance(
                        message, inputSnapshotSha256, conditionResult, true);
        if (existing.kind() == ExistingInstanceKind.CONFLICT) {
            return reject(message, duplicate, INSTANCE_CONFLICT, now);
        }
        if (existing.kind() == ExistingInstanceKind.SAME) {
            if (duplicate.recoverableDuplicate()) {
                completeRecoveryInbox(
                        message, duplicate.existing(), existing.workflowInstanceId(), now);
            }
            else {
                insertProcessedInbox(
                        message, inboxId(message), existing.workflowInstanceId(), now);
            }
            appendSuccessAudit(message, existing.workflowInstanceId(), now);
            return Completion.ack(
                    "WORKFLOW_START_EXISTING_INSTANCE", existing.workflowInstanceId());
        }

        UUID inboxId = inboxId(message);
        UUID instanceId = instanceId(message);
        if (!duplicate.recoverableDuplicate()) {
            insertProcessedInbox(message, inboxId, instanceId, now);
        }
        insertInstance(message, instanceId, inputSnapshotJson,
                inputSnapshotSha256, conditionResult, now);
        if (conditionResult) {
            UUID taskId = approvalTaskId(message.tenantId(), instanceId, 1);
            insertFirstLevelTask(message.tenantId(), taskId, instanceId,
                    version.level1ApproverUserId());
            if (notificationWriter != null) {
                notificationWriter.write(message.tenantId(), WorkflowNotificationIntent.create(
                        message.tenantId(), version.level1ApproverUserId(), taskId, instanceId,
                        1, message.correlationId(), message.messageId(), now));
            }
        }
        // 实例和反向通知在同一事务提交；无需审批时直接通过，并同时产生审批完成事件。
        insertInstanceEstablishedOutbox(message, instanceId,
                conditionResult ? "RUNNING" : "COMPLETED_PASSED", now);
        if (!conditionResult) {
            insertApprovalFinalizedOutbox(message, instanceId, "PASSED", now);
        }
        if (duplicate.recoverableDuplicate()) {
            completeRecoveryInbox(message, duplicate.existing(), instanceId, now);
        }
        appendSuccessAudit(message, instanceId, now);
        return Completion.ack("WORKFLOW_START_PROCESSED", instanceId);
    }

    /**
     * 将确定性启动失败保存为拒绝 Inbox 与审计，恢复输入同时结算原恢复记录。
     */
    private Completion reject(AcceptedWorkflowStart message,
                              DuplicateCheck duplicate,
                              String errorCode,
                              Instant now) {
        UUID inboxId;
        if (duplicate.recoverableDuplicate()) {
            inboxId = duplicate.existing().inboxId();
            rejectRecoveryInbox(message, duplicate.existing(), errorCode, now);
        }
        else {
            inboxId = inboxId(message);
            insertRejectedInbox(message, inboxId, errorCode, now);
        }
        insertAudit(
                message,
                auditId(message, "WORKFLOW_START_REJECTED", "INBOX_MESSAGE", inboxId,
                        errorCode),
                "WORKFLOW_START_REJECTED",
                "INBOX_MESSAGE",
                inboxId,
                "REJECTED",
                "Workflow start rejected by deterministic validation.",
                errorCode,
                now);
        return Completion.ack(errorCode, null);
    }

    /**
     * 追加启动消息身份或内容冲突审计，保留既有 Inbox 并拒绝覆盖原事实。
     */
    private void appendConflictAudit(AcceptedWorkflowStart message,
                                     DuplicateCheck duplicate,
                                     Instant now) {
        insertAudit(
                message,
                auditId(message, "WORKFLOW_START_CONFLICT", "INBOX_MESSAGE",
                        duplicate.existing().inboxId(), duplicate.conflictCode()),
                "WORKFLOW_START_CONFLICT",
                "INBOX_MESSAGE",
                duplicate.existing().inboxId(),
                "REJECTED",
                "Workflow start identity was reused with different content.",
                duplicate.conflictCode(),
                now);
    }

    /**
     * 记录启动处理成功审计，与实例和 Inbox 在同一事务中提交。
     */
    private void appendSuccessAudit(AcceptedWorkflowStart message,
                                    UUID workflowInstanceId,
                                    Instant now) {
        insertAudit(
                message,
                auditId(message, "WORKFLOW_START_CONSUMED", "WORKFLOW_INSTANCE",
                        workflowInstanceId, null),
                "WORKFLOW_START_CONSUMED",
                "WORKFLOW_INSTANCE",
                workflowInstanceId,
                "SUCCEEDED",
                null,
                null,
                now);
    }

    /**
     * 计算输入字节的 SHA-256 摘要，用于内容一致性核对及稳定业务键派生。
     */
    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 将启动条件输入编码为固定 JSON 结构，保留创建实例时的输入快照。
     */
    private static byte[] inputSnapshotJson(String serviceRiskLevel) {
        if (!("NORMAL".equals(serviceRiskLevel) || "HIGH".equals(serviceRiskLevel))) {
            throw new IllegalArgumentException("Unsupported Workflow input risk level");
        }
        return ("{\"schemaVersion\":\"1\",\"serviceRiskLevel\":\""
                + serviceRiskLevel + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 启动事务创建的全部身份均由不可变消息或业务意图派生。
     * Broker 重试与人工恢复重放因此落到同一批记录，避免重复创建 Inbox、实例、任务和审计锚点。
     */
    private static UUID inboxId(AcceptedWorkflowStart message) {
        return stableId("FD-WORKFLOW-START-INBOX-V1", message.tenantId(),
                message.messageId());
    }

    /**
     * 根据租户及工单启动身份生成稳定实例标识，使重复消息复用同一实例。
     */
    private static UUID instanceId(AcceptedWorkflowStart message) {
        return stableId("FD-WORKFLOW-INSTANCE-V1", message.tenantId(),
                message.ticketId(), message.businessIntentKey());
    }

    /**
     * 根据实例和审批级别生成稳定任务标识，防止重复创建同一级待办。
     */
    private static UUID approvalTaskId(UUID tenantId, UUID workflowInstanceId,
                                       int level) {
        return stableId("FD-WORKFLOW-APPROVAL-TASK-V1", tenantId,
                workflowInstanceId, level);
    }

    /**
     * 根据命名域和业务身份派生审计 UUID，使重复处理沿用同一审计锚点。
     */
    private static UUID auditId(AcceptedWorkflowStart message, String action,
                                String targetType, UUID targetId, String errorCode) {
        return stableId("FD-WORKFLOW-START-AUDIT-V1", message.tenantId(),
                message.messageId(), action, targetType,
                targetId == null ? new UUID(0L, 0L) : targetId,
                errorCode == null ? "" : errorCode);
    }

    /**
     * 按固定命名域和业务组成部分派生稳定标识，避免重试产生重复副作用。
     */
    private static UUID stableId(String namespace, Object... fields) {
        return WorkflowIdempotencyCodec.uuidFromDigest(
                WorkflowIdempotencyCodec.derivedKey(namespace, fields));
    }

    /**
     * 按声明的租户与业务条件查询消息重复事实，供调用方核对可见性、状态或幂等关系。
     */
    private DuplicateCheck findDuplicate(AcceptedWorkflowStart message, boolean lock) {
        List<InboxRecord> byMessage = mapper.findInboxByMessage(uuid(message.tenantId()), message.producerService(), uuid(message.messageId()),
                lock);
        if (!byMessage.isEmpty()) {
            InboxRecord existing = byMessage.get(0);
            return sameContent(existing, message)
                    ? DuplicateCheck.sameMessage(existing)
                    : DuplicateCheck.conflict(MESSAGE_CONFLICT, existing);
        }

        List<InboxRecord> byIntent = mapper.findInboxByIntent(uuid(message.tenantId()), message.producerService(), message.contractRef(),
                message.businessIntentKey(), lock);
        if (!byIntent.isEmpty()) {
            InboxRecord existing = byIntent.get(0);
            return sameContent(existing, message)
                    ? DuplicateCheck.sameIntent(existing)
                    : DuplicateCheck.conflict(INTENT_CONFLICT, existing);
        }
        return DuplicateCheck.fresh();
    }

    /**
     * 按声明的租户与业务条件查询流程版本，供调用方核对可见性、状态或幂等关系。
     */
    private VersionLookup findVersion(AcceptedWorkflowStart message) {
        List<VersionSnapshot> rows = mapper.findVersion(uuid(message.tenantId()), uuid(message.workflowVersionId()),
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

    /**
     * 按声明的租户与业务条件查询已有实例，供调用方核对可见性、状态或幂等关系。
     */
    private ExistingInstanceDecision findExistingInstance(AcceptedWorkflowStart message,
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

    /**
     * 持久化处理成功 Inbox，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    private void insertProcessedInbox(AcceptedWorkflowStart message,
                              UUID inboxId,
                              UUID workflowInstanceId,
                              Instant now) {
        mapper.insertProcessedInbox(uuid(message.tenantId()), uuid(inboxId), message.producerService(),
                uuid(message.messageId()), message.businessIntentKey(), message.contractRef(),
                message.contractVersion(), message.bodyBytes(), message.bodySha256(),
                uuid(message.correlationId()), nullableUuid(message.causationId()),
                Timestamp.from(message.occurredAt()), uuid(workflowInstanceId), Timestamp.from(now));
    }

    /**
     * 持久化确定性拒绝 Inbox，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    private void insertRejectedInbox(AcceptedWorkflowStart message,
                              UUID inboxId,
                              String errorCode,
                              Instant now) {
        insertRejectedInbox(message, inboxId, errorCode, 1, now);
    }

    /**
     * 持久化确定性拒绝 Inbox，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    private void insertRejectedInbox(AcceptedWorkflowStart message,
                              UUID inboxId,
                              String errorCode,
                              int attemptCount,
                              Instant now) {
        mapper.insertRejectedInbox(uuid(message.tenantId()), uuid(inboxId), message.producerService(),
                uuid(message.messageId()), message.businessIntentKey(), message.contractRef(),
                message.contractVersion(), message.bodyBytes(), message.bodySha256(),
                uuid(message.correlationId()), nullableUuid(message.causationId()),
                Timestamp.from(message.occurredAt()), attemptCount, errorCode, Timestamp.from(now));
    }



    /**
     * 按声明的租户与业务条件查询Inbox 配对恢复记录，供调用方核对可见性、状态或幂等关系。
     */
    private RecoveryRecord findInboxRecovery(UUID tenantId, UUID inboxId, boolean lock) {
        List<RecoveryRecord> rows = mapper.findInboxRecovery(uuid(tenantId), uuid(inboxId), lock);
        return rows.isEmpty() ? null : rows.get(0);
    }



    /**
     * 完成待恢复 Inbox 并保存业务结果，与配对恢复记录共同保持一致。
     */
    private void completeRecoveryInbox(AcceptedWorkflowStart message,
                               InboxRecord inbox,
                               UUID workflowInstanceId,
                               Instant now) {
        int changed = mapper.completeRecoveryInbox(uuid(workflowInstanceId), Timestamp.from(now), uuid(message.tenantId()),
                uuid(inbox.inboxId()), message.producerService(), uuid(message.messageId()),
                message.contractRef(), message.contractVersion(), message.businessIntentKey(),
                message.bodySha256(), inbox.lockVersion());
        requireSingleRecoveryChange(changed, "Inbox processing");
        resolveInboxRecovery(message.tenantId(), inbox.inboxId(), now);
    }

    /**
     * 记录待恢复启动消息的确定性拒绝，并结算配对恢复生命周期。
     */
    private void rejectRecoveryInbox(AcceptedWorkflowStart message,
                             InboxRecord inbox,
                             String errorCode,
                             Instant now) {
        int changed = mapper.rejectRecoveryInbox(errorCode, Timestamp.from(now), uuid(message.tenantId()), uuid(inbox.inboxId()),
                message.producerService(), uuid(message.messageId()), message.contractRef(),
                message.contractVersion(), message.businessIntentKey(), message.bodySha256(),
                inbox.lockVersion());
        requireSingleRecoveryChange(changed, "Inbox rejection");
        resolveInboxRecovery(message.tenantId(), inbox.inboxId(), now);
    }

    /**
     * 结清指定 Inbox 的配对恢复记录，须与原消息处理在同一事务执行。
     */
    private void resolveInboxRecovery(UUID tenantId, UUID inboxId, Instant now) {
        RecoveryRecord recovery = findInboxRecovery(tenantId, inboxId, true);
        if (recovery == null) {
            throw new ConcurrencyFailureException(
                    "RECOVERY_REQUIRED Inbox has no matching INBOX Recovery");
        }
        if ("RESOLVED".equals(recovery.state())) {
            return;
        }
        int changed = mapper.resolveInboxRecovery(Timestamp.from(now), uuid(tenantId), uuid(recovery.recoveryId()),
                uuid(inboxId), recovery.lockVersion());
        requireSingleRecoveryChange(changed, "Recovery resolution");
    }

    /**
     * 断言恢复迁移恰好更新一行，否则回滚以防 Inbox 与恢复事实不一致。
     */
    private static void requireSingleRecoveryChange(int changed, String operation) {
        if (changed != 1) {
            throw new ConcurrencyFailureException(operation + " lost its conditional update");
        }
    }

    /**
     * 持久化工作流实例，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    private void insertInstance(AcceptedWorkflowStart message,
                        UUID workflowInstanceId,
                        byte[] inputSnapshotJson,
                        byte[] inputSnapshotSha256,
                        boolean conditionResult,
                        Instant now) {
        String status = conditionResult ? "RUNNING" : "COMPLETED_PASSED";
        Integer currentLevel = conditionResult ? 1 : null;
        Timestamp completedAt = conditionResult ? null : Timestamp.from(now);
        mapper.insertInstance(uuid(message.tenantId()), uuid(workflowInstanceId), uuid(message.ticketId()),
                message.businessIntentKey(), uuid(message.workflowDefinitionId()),
                uuid(message.workflowVersionId()), message.versionNo(),
                new String(inputSnapshotJson, java.nio.charset.StandardCharsets.UTF_8),
                inputSnapshotSha256, conditionResult, status, currentLevel, Timestamp.from(now),
                completedAt);
    }

    /**
     * 持久化首级任务，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    private void insertFirstLevelTask(UUID tenantId,
                              UUID taskId,
                              UUID workflowInstanceId,
                              UUID approverUserId) {
        mapper.insertFirstLevelTask(uuid(tenantId), uuid(taskId), uuid(workflowInstanceId), uuid(approverUserId));
    }

    /**
     * 持久化实例建立反向事件，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    private void insertInstanceEstablishedOutbox(AcceptedWorkflowStart message,
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

    /**
     * 持久化审批完成反向事件，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    private void insertApprovalFinalizedOutbox(AcceptedWorkflowStart message,
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

    /**
     * 在当前启动事务内写入工作流拥有的通知请求。
     */
    private void insertOutbox(UUID tenantId, UUID workflowInstanceId,
                              String contractRef, byte[] businessIntentKey,
                              byte[] body, UUID correlationId, UUID causationId,
                              Instant now) {
        UUID outboxId = WorkflowReverseEventCodec.outboxId(tenantId, contractRef, businessIntentKey);
        UUID messageId = WorkflowReverseEventCodec.messageId(tenantId, contractRef, businessIntentKey);
        mapper.insertInstanceEstablishedOutbox(uuid(tenantId), uuid(outboxId), uuid(messageId),
                WorkflowIdempotencyCodec.publicationKey(tenantId, messageId), businessIntentKey,
                uuid(workflowInstanceId), contractRef, body, WorkflowIdempotencyCodec.sha256(body),
                uuid(correlationId), nullableUuid(causationId), Timestamp.from(now));
    }

    /**
     * 持久化审计记录，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    private void insertAudit(AcceptedWorkflowStart message,
                     UUID auditId,
                     String actionCode,
                     String targetType,
                     UUID targetId,
                     String outcome,
                     String reason,
                     String errorCode,
                     Instant now) {
        mapper.insertAudit(uuid(message.tenantId()), uuid(auditId), actionCode, targetType, nullableUuid(targetId),
                outcome, reason, errorCode, uuid(message.correlationId()),
                nullableUuid(message.causationId()), Timestamp.from(now));
    }

    /**
     * 按声明的租户与业务条件查询工作流实例，供调用方核对可见性、状态或幂等关系。
     */
    private List<InstanceRecord> findInstances(String field,
                                               byte[] value,
                                               UUID tenantId,
                                               boolean lock) {
        return mapper.findInstances(uuid(tenantId), value, "ticket_id".equals(field), lock);
    }

    /**
     * 比较契约、意图键与内容摘要，区分安全重复投递和身份内容冲突。
     */
    private static boolean sameContent(InboxRecord existing, AcceptedWorkflowStart incoming) {
        return existing.contractRef().equals(incoming.contractRef())
                && existing.contractVersion() == incoming.contractVersion()
                && MessageDigest.isEqual(existing.businessIntentKey(), incoming.businessIntentKey())
                && MessageDigest.isEqual(existing.bodySha256(), incoming.bodySha256());
    }

    /**
     * 核对已有实例的意图、版本和输入快照，防止工单关联不同启动事实。
     */
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

    // 数据库使用固定 16 字节 UUID 表示，跨查询和写入保持同一字节顺序。
    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    /**
     * 转换可空 UUID，空值保持为空以适配可选数据库字段。
     */
    private static byte[] nullableUuid(UUID value) {
        return value == null ? null : uuid(value);
    }

    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /**
     * 转换可空 UUID，空值保持为空以适配可选数据库字段。
     */
    private static UUID nullableUuid(byte[] value) {
        return value == null ? null : uuid(value);
    }

}
