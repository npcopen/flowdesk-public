package com.flowdesk.workflow.start;

import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;

import java.util.Objects;

/** Executes exactly one accepted manual attempt through the normal consumer entry. */
final class WorkflowRecoveryReplayWorker {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowRecoveryReplayWorker.class);

    private final TaskExecutor executor;
    private final WorkflowRecoveryRepository repository;
    private final WorkflowStartConsumer consumer;
    private final WorkflowRecoveryReplaySettlement settlement;

    WorkflowRecoveryReplayWorker(TaskExecutor executor,
                                 WorkflowRecoveryRepository repository,
                                 WorkflowStartConsumer consumer,
                                 WorkflowRecoveryReplaySettlement settlement) {
        this.executor = executor;
        this.repository = repository;
        this.consumer = consumer;
        this.settlement = settlement;
    }

    void submit(WorkflowRecoveryReplayTransaction.ReplayWork work) {
        Objects.requireNonNull(work, "work");
        try {
            executor.execute(() -> executeSafely(work));
        }
        catch (RuntimeException rejectedSubmission) {
            settlement.failed(work, "RECOVERY_REPLAY_WORKER_UNAVAILABLE");
            LOG.error("Workflow recovery replay work could not be submitted");
        }
    }

    private void executeSafely(WorkflowRecoveryReplayTransaction.ReplayWork work) {
        TenantContext taskContext = TenantContext.ofPersistedTask(
                work.tenantId(), ActorRef.human(work.actorId()), work.attemptCorrelationId());
        try (TenantContextScope ignored = TenantContextScope.open(taskContext)) {
            WorkflowRecoveryRepository.RecoveryFact recovery = repository
                    .findRecovery(work.tenantId(), work.recoveryId(), false)
                    .orElse(null);
            WorkflowRecoveryRepository.ReplaySource source = repository
                    .findSource(work.tenantId(), work.sourceRecordId(), false)
                    .orElse(null);
            if (!isCurrentWork(work, recovery, source)) {
                return;
            }

            WorkflowStartTransportMessage restored = WorkflowStartReplayEnvelope.restore(source);
            WorkflowStartConsumeResult result = consumer.consume(restored);
            if (result.disposition() == WorkflowStartConsumeResult.BrokerDisposition.RETRY) {
                settlement.failed(work, result.code());
            }
            else {
                settlement.completed(work, result);
            }
        }
        catch (RuntimeException failedAttempt) {
            settlement.failed(work, "RECOVERY_REPLAY_EXECUTION_FAILED");
            LOG.warn("Workflow recovery replay attempt failed before terminal settlement");
        }
    }

    private static boolean isCurrentWork(
            WorkflowRecoveryReplayTransaction.ReplayWork work,
            WorkflowRecoveryRepository.RecoveryFact recovery,
            WorkflowRecoveryRepository.ReplaySource source) {
        return recovery != null
                && source != null
                && source.tenantId().equals(work.tenantId())
                && recovery.sourceRecordId().equals(source.inboxId())
                && "INBOX".equals(recovery.direction())
                && "REPLAYING".equals(recovery.state())
                && work.attemptCorrelationId().equals(recovery.lastReplayCorrelationId())
                && "RECOVERY_REQUIRED".equals(source.status());
    }
}
