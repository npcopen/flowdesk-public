package com.flowdesk.workflow.start;

import com.flowdesk.workflow.service.recovery.WorkflowRecoveryExecutionService;
import com.flowdesk.workflow.model.recovery.WorkflowRecoveryModels.*;
import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.common.recovery.RecoveryReplayResumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;

import java.util.Objects;

/**
 * 经正常消费入口执行已受理的人工重放；利用尝试标识和租约排除过期任务。
 */
public final class WorkflowRecoveryReplayWorker {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowRecoveryReplayWorker.class);

    private final TaskExecutor executor;
    private final WorkflowStartConsumer consumer;
    private final WorkflowRecoveryExecutionService settlement;

    /**
     * 绑定异步执行器、原启动消费入口和恢复结算服务，后台重放沿用原消息处理路径并检查持久化租约。
     */
    public WorkflowRecoveryReplayWorker(TaskExecutor executor,
                                        WorkflowStartConsumer consumer,
                                 WorkflowRecoveryExecutionService settlement) {
        this.executor = executor;
        this.consumer = consumer;
        this.settlement = settlement;
    }

    /**
     * 向执行器提交已持久化工作项，实际处理前仍需核验当前租约身份。
     */
    public void submit(ReplayWork work) {
        Objects.requireNonNull(work, "work");
        try {
            executor.execute(() -> executeSafely(work));
        }
        catch (RuntimeException rejectedSubmission) {
            settlement.failed(work, "RECOVERY_REPLAY_WORKER_UNAVAILABLE");
            LOG.error("Workflow recovery replay work could not be submitted");
        }
    }

    /**
     * 将已提交恢复任务还原为工作项，沿用原重放身份继续执行重启前未完成的尝试。
     */
    public void resume(RecoveryReplayResumer.Task task) {
        submit(new ReplayWork(task.tenantId(), task.recoveryId(),
                task.sourceRecordId(), task.actorId(), task.reason(), task.attemptCorrelationId(),
                task.sourceCorrelationId(), task.leaseUntil()));
    }

    /**
     * 执行重放并统一结算异常，避免后台退出留下无处理者的执行中状态。
     */
    private void executeSafely(ReplayWork work) {
        TenantContext taskContext = TenantContext.ofPersistedTask(
                work.tenantId(), ActorRef.human(work.actorId()), work.attemptCorrelationId());
        try (TenantContextScope ignored = TenantContextScope.open(taskContext)) {
            var current = settlement.currentSource(work);
            if (current.isEmpty()) return;
            ReplaySource source = current.get();
            if ("PROCESSED".equals(source.status()) || "REJECTED".equals(source.status())) {
                settlement.completed(work, WorkflowStartConsumeResult.ack("WORKFLOW_START_DUPLICATE", null));
                return;
            }
            if (!"RECOVERY_REQUIRED".equals(source.status())) {
                settlement.failed(work, "RECOVERY_REPLAY_NOT_TERMINAL");
                return;
            }

            // 恢复原始信封并复用正常消费入口，使人工重放遵守同一校验、去重和事务规则。
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

}
