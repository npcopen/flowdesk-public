package com.flowdesk.workflow.start;

import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.tenant.TenantContextSource;
import com.flowdesk.workflow.service.WorkflowIdempotencyCodec;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Atomic DLQ classification and acceptance transaction. */
final class WorkflowStartDlqTransaction {

    static final String RETRIES_EXHAUSTED = "BROKER_RETRIES_EXHAUSTED";
    private static final String RECOVERY_SUMMARY =
            "Main Workflow consumer exhausted its bounded Broker deliveries.";

    private final WorkflowStartRepository repository;
    private final TransactionTemplate transactions;

    WorkflowStartDlqTransaction(WorkflowStartRepository repository,
                                PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    WorkflowStartConsumeResult accept(AcceptedWorkflowStart message) {
        WorkflowStartConsumeResult result = transactions.execute(status -> acceptInside(message));
        if (result == null) {
            throw new IllegalStateException("Workflow-start DLQ transaction returned no decision");
        }
        return result;
    }

    private WorkflowStartConsumeResult acceptInside(AcceptedWorkflowStart message) {
        var context = TenantContextHolder.current();
        context.requireTenant(message.tenantId());
        if (context.source() != TenantContextSource.BROKER_MESSAGE) {
            throw new IllegalStateException("Workflow-start DLQ acceptance requires Broker context");
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        WorkflowStartRepository.DuplicateCheck duplicate =
                repository.findDuplicate(message, true);
        if (duplicate.terminalDuplicate()) {
            return WorkflowStartConsumeResult.ack(
                    "WORKFLOW_START_DLQ_DUPLICATE", duplicate.existing().resultId());
        }
        if (duplicate.kind() == WorkflowStartRepository.DuplicateKind.CONFLICT) {
            repository.insertAudit(message, auditId(message, "WORKFLOW_START_DLQ_CONFLICT",
                            "INBOX_MESSAGE", duplicate.existing().inboxId(),
                            duplicate.conflictCode()),
                    "WORKFLOW_START_DLQ_CONFLICT", "INBOX_MESSAGE",
                    duplicate.existing().inboxId(), "REJECTED",
                    "DLQ identity was reused with different content.",
                    duplicate.conflictCode(), now);
            return WorkflowStartConsumeResult.ack(duplicate.conflictCode(), null);
        }
        if (duplicate.recoverableDuplicate()) {
            WorkflowStartRepository.RecoveryRecord recovery = repository.findInboxRecovery(
                    message.tenantId(), duplicate.existing().inboxId(), true);
            if (recovery == null) {
                throw new ConcurrencyFailureException(
                        "RECOVERY_REQUIRED Inbox is missing its atomic Recovery fact");
            }
            return WorkflowStartConsumeResult.ack(
                    "WORKFLOW_START_DLQ_RECOVERY_DUPLICATE", null);
        }

        WorkflowStartRepository.VersionLookup version = repository.findVersion(message);
        if (!version.valid()) {
            UUID inboxId = inboxId(message);
            repository.insertRejectedInbox(message, inboxId, version.errorCode(),
                    WorkflowStartContract.MAIN_DELIVERY_ATTEMPTS, now);
            repository.insertAudit(message, auditId(message, "WORKFLOW_START_DLQ_REJECTED",
                            "INBOX_MESSAGE", inboxId, version.errorCode()),
                    "WORKFLOW_START_DLQ_REJECTED", "INBOX_MESSAGE", inboxId,
                    "REJECTED", "DLQ message failed deterministic reference validation.",
                    version.errorCode(), now);
            return WorkflowStartConsumeResult.ack(version.errorCode(), null);
        }

        UUID inboxId = inboxId(message);
        UUID recoveryId = recoveryId(message, inboxId);
        repository.insertRecoveryRequiredInbox(message, inboxId, RETRIES_EXHAUSTED, now);
        repository.insertInboxRecovery(message, inboxId, recoveryId, RETRIES_EXHAUSTED,
                RECOVERY_SUMMARY, now);
        repository.insertAudit(message, auditId(message, "WORKFLOW_START_DLQ_ACCEPTED",
                        "MESSAGE_RECOVERY", recoveryId, RETRIES_EXHAUSTED),
                "WORKFLOW_START_DLQ_ACCEPTED", "MESSAGE_RECOVERY", recoveryId,
                "FAILED", RECOVERY_SUMMARY, RETRIES_EXHAUSTED, now);
        return WorkflowStartConsumeResult.ack("WORKFLOW_START_RECOVERY_REQUIRED", null);
    }

    private static UUID inboxId(AcceptedWorkflowStart message) {
        return stableId("FD-WORKFLOW-START-INBOX-V1", message.tenantId(),
                message.messageId());
    }

    private static UUID recoveryId(AcceptedWorkflowStart message, UUID inboxId) {
        return stableId("FD-WORKFLOW-START-RECOVERY-V1", message.tenantId(), inboxId);
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
}
