package com.flowdesk.workflow.start;

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

/** The one local transaction that re-decides identity and commits every local effect. */
final class WorkflowStartTransaction {

    private static final String VERSION_CHANGED = "WORKFLOW_VERSION_CONTENT_CHANGED";
    private static final String APPROVER_NOT_ELIGIBLE = "WORKFLOW_APPROVER_NOT_ELIGIBLE";

    private final WorkflowStartRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final WorkflowNotificationOutboxWriter notificationWriter;

    WorkflowStartTransaction(WorkflowStartRepository repository,
                             PlatformTransactionManager transactionManager) {
        this(repository, transactionManager, Clock.systemUTC(), null);
    }

    WorkflowStartTransaction(WorkflowStartRepository repository,
                             PlatformTransactionManager transactionManager,
                             Clock clock,
                             WorkflowNotificationOutboxWriter notificationWriter) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.notificationWriter = notificationWriter;
    }

    Completion complete(AcceptedWorkflowStart message, Preflight preflight) {
        Completion completion = transactions.execute(status -> completeInside(message, preflight));
        if (completion == null) {
            throw new IllegalStateException("Workflow-start transaction returned no decision");
        }
        return completion;
    }

    private Completion completeInside(AcceptedWorkflowStart message, Preflight preflight) {
        var context = TenantContextHolder.current();
        context.requireTenant(message.tenantId());
        if (context.source() != TenantContextSource.BROKER_MESSAGE) {
            throw new IllegalStateException("Workflow-start requires a Broker TenantContext");
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        WorkflowStartRepository.DuplicateCheck duplicate =
                repository.findDuplicate(message, true);
        if (duplicate.terminalDuplicate()) {
            return Completion.duplicate(duplicate.existing().resultId());
        }
        if (duplicate.kind() == WorkflowStartRepository.DuplicateKind.CONFLICT) {
            appendConflictAudit(message, duplicate, now);
            return Completion.ack(duplicate.conflictCode(), null);
        }
        if (preflight.kind() == PreflightKind.CONFLICT_ONLY) {
            return Completion.needsPreflight();
        }

        WorkflowStartRepository.VersionLookup currentVersion = repository.findVersion(message);
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

        WorkflowStartRepository.VersionSnapshot version = currentVersion.version();
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

        WorkflowStartRepository.ExistingInstanceDecision existing =
                repository.findExistingInstance(
                        message, inputSnapshotSha256, conditionResult, true);
        if (existing.kind() == WorkflowStartRepository.ExistingInstanceKind.CONFLICT) {
            return reject(message, duplicate, WorkflowStartRepository.INSTANCE_CONFLICT, now);
        }
        if (existing.kind() == WorkflowStartRepository.ExistingInstanceKind.SAME) {
            if (duplicate.recoverableDuplicate()) {
                repository.completeRecoveryInbox(
                        message, duplicate.existing(), existing.workflowInstanceId(), now);
            }
            else {
                repository.insertProcessedInbox(
                        message, inboxId(message), existing.workflowInstanceId(), now);
            }
            appendSuccessAudit(message, existing.workflowInstanceId(), now);
            return Completion.ack(
                    "WORKFLOW_START_EXISTING_INSTANCE", existing.workflowInstanceId());
        }

        UUID inboxId = inboxId(message);
        UUID instanceId = instanceId(message);
        if (!duplicate.recoverableDuplicate()) {
            repository.insertProcessedInbox(message, inboxId, instanceId, now);
        }
        repository.insertInstance(message, instanceId, inputSnapshotJson,
                inputSnapshotSha256, conditionResult, now);
        if (conditionResult) {
            UUID taskId = approvalTaskId(message.tenantId(), instanceId, 1);
            repository.insertFirstLevelTask(message.tenantId(), taskId, instanceId,
                    version.level1ApproverUserId());
            if (notificationWriter != null) {
                notificationWriter.write(message.tenantId(), WorkflowNotificationIntent.create(
                        message.tenantId(), version.level1ApproverUserId(), taskId, instanceId,
                        1, message.correlationId(), message.messageId(), now));
            }
        }
        repository.insertInstanceEstablishedOutbox(message, instanceId,
                conditionResult ? "RUNNING" : "COMPLETED_PASSED", now);
        if (!conditionResult) {
            repository.insertApprovalFinalizedOutbox(message, instanceId, "PASSED", now);
        }
        if (duplicate.recoverableDuplicate()) {
            repository.completeRecoveryInbox(message, duplicate.existing(), instanceId, now);
        }
        appendSuccessAudit(message, instanceId, now);
        return Completion.ack("WORKFLOW_START_PROCESSED", instanceId);
    }

    private Completion reject(AcceptedWorkflowStart message,
                              WorkflowStartRepository.DuplicateCheck duplicate,
                              String errorCode,
                              Instant now) {
        UUID inboxId;
        if (duplicate.recoverableDuplicate()) {
            inboxId = duplicate.existing().inboxId();
            repository.rejectRecoveryInbox(message, duplicate.existing(), errorCode, now);
        }
        else {
            inboxId = inboxId(message);
            repository.insertRejectedInbox(message, inboxId, errorCode, now);
        }
        repository.insertAudit(
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

    private void appendConflictAudit(AcceptedWorkflowStart message,
                                     WorkflowStartRepository.DuplicateCheck duplicate,
                                     Instant now) {
        repository.insertAudit(
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

    private void appendSuccessAudit(AcceptedWorkflowStart message,
                                    UUID workflowInstanceId,
                                    Instant now) {
        repository.insertAudit(
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

    static Preflight ready(WorkflowStartRepository.VersionSnapshot version,
                           UUID eligibleApproverUserId) {
        return new Preflight(PreflightKind.START, version, eligibleApproverUserId, null);
    }

    static Preflight rejected(WorkflowStartRepository.VersionSnapshot version,
                              String errorCode) {
        return new Preflight(PreflightKind.REJECT, version, null, errorCode);
    }

    static Preflight conflictOnly() {
        return new Preflight(PreflightKind.CONFLICT_ONLY, null, null, null);
    }

    static String approverNotEligibleCode() {
        return APPROVER_NOT_ELIGIBLE;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static byte[] inputSnapshotJson(String serviceRiskLevel) {
        if (!("NORMAL".equals(serviceRiskLevel) || "HIGH".equals(serviceRiskLevel))) {
            throw new IllegalArgumentException("Unsupported Workflow input risk level");
        }
        return ("{\"schemaVersion\":\"1\",\"serviceRiskLevel\":\""
                + serviceRiskLevel + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * All identities created by the start transaction are derived from the
     * immutable message/business intent.  A broker retry or a Recovery replay
     * therefore converges on the same rows instead of allocating a second
     * Inbox, instance, task, or audit anchor.
     */
    private static UUID inboxId(AcceptedWorkflowStart message) {
        return stableId("FD-WORKFLOW-START-INBOX-V1", message.tenantId(),
                message.messageId());
    }

    private static UUID instanceId(AcceptedWorkflowStart message) {
        return stableId("FD-WORKFLOW-INSTANCE-V1", message.tenantId(),
                message.ticketId(), message.businessIntentKey());
    }

    private static UUID approvalTaskId(UUID tenantId, UUID workflowInstanceId,
                                       int level) {
        return stableId("FD-WORKFLOW-APPROVAL-TASK-V1", tenantId,
                workflowInstanceId, level);
    }

    private static UUID auditId(AcceptedWorkflowStart message, String action,
                                String targetType, UUID targetId, String errorCode) {
        return stableId("FD-WORKFLOW-START-AUDIT-V1", message.tenantId(),
                message.messageId(), action, targetType,
                targetId == null ? new UUID(0L, 0L) : targetId,
                errorCode == null ? "" : errorCode);
    }

    private static UUID stableId(String namespace, Object... fields) {
        return WorkflowIdempotencyCodec.uuidFromDigest(
                WorkflowIdempotencyCodec.derivedKey(namespace, fields));
    }

    enum PreflightKind {
        START,
        REJECT,
        CONFLICT_ONLY
    }

    record Preflight(PreflightKind kind,
                     WorkflowStartRepository.VersionSnapshot version,
                     UUID eligibleApproverUserId,
                     String errorCode) {
    }

    record Completion(CompletionKind kind, String code, UUID workflowInstanceId) {

        static Completion ack(String code, UUID workflowInstanceId) {
            return new Completion(CompletionKind.ACK, code, workflowInstanceId);
        }

        static Completion duplicate(UUID workflowInstanceId) {
            return ack("WORKFLOW_START_DUPLICATE", workflowInstanceId);
        }

        static Completion needsPreflight() {
            return new Completion(CompletionKind.NEEDS_PREFLIGHT,
                    "WORKFLOW_START_PREFLIGHT_REQUIRED", null);
        }
    }

    enum CompletionKind {
        ACK,
        NEEDS_PREFLIGHT
    }
}
