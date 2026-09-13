package com.flowdesk.workflow.start;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Persists the one local replay-attempt outcome without touching source message facts. */
final class WorkflowRecoveryReplaySettlement {

    private static final String FAILURE_SUMMARY =
            "The controlled same-path replay did not reach a terminal local decision.";

    private final WorkflowRecoveryRepository repository;
    private final TransactionTemplate transactions;

    WorkflowRecoveryReplaySettlement(WorkflowRecoveryRepository repository,
                                     PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    void completed(WorkflowRecoveryReplayTransaction.ReplayWork work,
                   WorkflowStartConsumeResult result) {
        transactions.executeWithoutResult(status -> {
            WorkflowRecoveryRepository.ReplaySource source = repository
                    .findSource(work.tenantId(), work.sourceRecordId(), true)
                    .orElse(null);
            WorkflowRecoveryRepository.RecoveryFact recovery = repository
                    .findRecovery(work.tenantId(), work.recoveryId(), true)
                    .orElse(null);
            if (recovery == null || source == null
                    || !"RESOLVED".equals(recovery.state())
                    || !("PROCESSED".equals(source.status())
                    || "REJECTED".equals(source.status()))) {
                reopenInside(work, "RECOVERY_REPLAY_NOT_TERMINAL");
                return;
            }
            String outcome = "PROCESSED".equals(source.status()) ? "SUCCEEDED" : "REJECTED";
            repository.insertReplayOutcomeAudit(
                    work.tenantId(),
                    WorkflowRecoveryIdempotency.outcomeAuditId(
                            work.tenantId(), work.attemptCorrelationId(), outcome),
                    work.recoveryId(), work.actorId(), outcome, work.reason(),
                    "REJECTED".equals(outcome) ? result.code() : null,
                    work.attemptCorrelationId(), work.sourceCorrelationId(), now());
        });
    }

    void failed(WorkflowRecoveryReplayTransaction.ReplayWork work, String errorCode) {
        transactions.executeWithoutResult(status -> reopenInside(work, errorCode));
    }

    private void reopenInside(WorkflowRecoveryReplayTransaction.ReplayWork work,
                              String errorCode) {
        Instant now = now();
        int changed = repository.reopenFailedReplay(
                work.tenantId(), work.recoveryId(), work.attemptCorrelationId(),
                errorCode, FAILURE_SUMMARY, now);
        if (changed == 1) {
            repository.insertReplayOutcomeAudit(
                    work.tenantId(),
                    WorkflowRecoveryIdempotency.outcomeAuditId(
                            work.tenantId(), work.attemptCorrelationId(), "FAILED"),
                    work.recoveryId(), work.actorId(), "FAILED", work.reason(), errorCode,
                    work.attemptCorrelationId(), work.sourceCorrelationId(), now);
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
