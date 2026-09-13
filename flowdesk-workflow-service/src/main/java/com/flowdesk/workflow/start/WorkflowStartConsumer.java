package com.flowdesk.workflow.start;

import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

import java.util.UUID;

/**
 * Strict five-stage Workflow consumption entry. A Broker adapter may ACK only when
 * this class returns {@link WorkflowStartConsumeResult.BrokerDisposition#ACK}.
 */
public final class WorkflowStartConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowStartConsumer.class);
    private static final String DB_UNAVAILABLE = "WORKFLOW_DB_UNAVAILABLE";
    private static final String IAM_UNAVAILABLE = "IAM_ELIGIBILITY_UNAVAILABLE";

    private final WorkflowStartEnvelopeGuard guard;
    private final WorkflowStartRepository repository;
    private final WorkflowStartTransaction transaction;
    private final WorkflowApproverEligibilityChecker eligibilityChecker;

    WorkflowStartConsumer(WorkflowStartEnvelopeGuard guard,
                          WorkflowStartRepository repository,
                          WorkflowStartTransaction transaction,
                          WorkflowApproverEligibilityChecker eligibilityChecker) {
        this.guard = guard;
        this.repository = repository;
        this.transaction = transaction;
        this.eligibilityChecker = eligibilityChecker;
    }

    public WorkflowStartConsumeResult consume(WorkflowStartTransportMessage delivery) {
        final AcceptedWorkflowStart message;
        try {
            // 1. Transport guard. No tenant-owned read or write is reachable before this.
            message = guard.validateMain(delivery);
        }
        catch (WorkflowStartContractViolation deterministicPoison) {
            LOG.warn("Workflow-start delivery ACKed by transport guard; code={}",
                    deterministicPoison.code());
            return WorkflowStartConsumeResult.ack(deterministicPoison.code(), null);
        }

        // 2. Install the only trusted context accepted by this message entry.
        TenantContext context = TenantContext.ofValidatedBrokerMessage(
                message.tenantId(), message.producerService(), message.correlationId());
        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            try {
                return consumeInsideTenant(message);
            }
            catch (WorkflowIamUnavailableException unavailable) {
                LOG.warn("Workflow-start delivery returned to Broker; dependency=IAM");
                return WorkflowStartConsumeResult.retry(IAM_UNAVAILABLE);
            }
            catch (DataAccessException | TransactionException unavailable) {
                LOG.warn("Workflow-start delivery returned to Broker; dependency=DATABASE");
                return WorkflowStartConsumeResult.retry(DB_UNAVAILABLE);
            }
        }
    }

    private WorkflowStartConsumeResult consumeInsideTenant(AcceptedWorkflowStart message) {
        // 3. Read-only duplicate fast path in the frozen message-id then intent order.
        WorkflowStartRepository.DuplicateCheck fast = repository.findDuplicate(message, false);
        if (fast.terminalDuplicate()) {
            return WorkflowStartConsumeResult.ack(
                    "WORKFLOW_START_DUPLICATE", fast.existing().resultId());
        }
        if (fast.kind() == WorkflowStartRepository.DuplicateKind.CONFLICT) {
            WorkflowStartTransaction.Completion settled =
                    transaction.complete(message, WorkflowStartTransaction.conflictOnly());
            if (settled.kind() == WorkflowStartTransaction.CompletionKind.ACK) {
                return WorkflowStartConsumeResult.ack(
                        settled.code(), settled.workflowInstanceId());
            }
            // Inbox facts are immutable in the runtime account. This branch only covers
            // an out-of-band deletion between the fast read and the locked redecision.
        }

        // 4. WorkflowVersion and, only when a first task is necessary, IAM pre-check.
        WorkflowStartRepository.VersionLookup version = repository.findVersion(message);
        WorkflowStartTransaction.Preflight preflight;
        if (!version.valid()) {
            preflight = WorkflowStartTransaction.rejected(null, version.errorCode());
        }
        else if (version.version().requiresApproval(message)) {
            UUID approver = version.version().level1ApproverUserId();
            if (!eligibilityChecker.isEligibleApprover(message.tenantId(), approver)) {
                // M6 reclassifies a negative IAM qualification as a recoverable
                // external qualification failure.  No local Inbox/Instance/Task/
                // Outbox fact may be committed before the main Group exhausts its
                // bounded delivery budget and the DLQ acceptor owns recovery.
                return WorkflowStartConsumeResult.retry(
                        WorkflowStartTransaction.approverNotEligibleCode());
            }
            else {
                preflight = WorkflowStartTransaction.ready(version.version(), approver);
            }
        }
        else {
            preflight = WorkflowStartTransaction.ready(version.version(), null);
        }

        // 5. Lock/re-decide locally, then atomically commit Inbox/effects/Audit.
        WorkflowStartTransaction.Completion completion = transaction.complete(message, preflight);
        if (completion.kind() != WorkflowStartTransaction.CompletionKind.ACK) {
            throw new IllegalStateException("Workflow-start preflight unexpectedly disappeared");
        }
        return WorkflowStartConsumeResult.ack(
                completion.code(), completion.workflowInstanceId());
    }
}
