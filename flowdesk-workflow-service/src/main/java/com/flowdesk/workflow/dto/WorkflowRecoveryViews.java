package com.flowdesk.workflow.dto;
import java.util.HexFormat;
/**
 * 工作流内部协作类型，组合当前处理所需的结构与行为，供所在用例的依赖链调用。
 */
public final class WorkflowRecoveryViews {
    /**
     * 禁止直接实例化工具容器，当前类型通过静态方法提供共享规则和转换。
     */
    private WorkflowRecoveryViews() {}
    /**
     * 将恢复事实映射为统一视图，展示状态、失败原因及重放信息。
     */
    public static WorkflowRecoveryView toView(com.flowdesk.workflow.model.recovery.WorkflowRecoveryModels.RecoveryFact fact) {
        WorkflowRecoveryView.ReplayedBy replayedBy =
                fact.lastReplayedById() == null ? null
                        : new WorkflowRecoveryView.ReplayedBy(
                                fact.lastReplayedByType(), fact.lastReplayedById());
        return new WorkflowRecoveryView(
                fact.recoveryId(), "WORKFLOW", fact.direction(), fact.sourceRecordId(),
                HexFormat.of().formatHex(fact.businessIntentKey()), fact.state(),
                fact.attemptCount(), fact.nextAttemptAt(), fact.lastErrorCode(),
                fact.lastErrorSummary(), fact.deadLetteredAt(), fact.recoveryRequiredAt(),
                fact.replayCount(), fact.lastReplayedAt(), replayedBy,
                fact.lastReplayReason(), fact.resolvedAt(), fact.lockVersion(),
                fact.createdAt(), fact.updatedAt());
    }
}
