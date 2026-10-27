package com.flowdesk.workflow.service;

import com.flowdesk.workflow.dto.WorkflowDtos;
import java.util.List;
import java.util.UUID;

/**
 * 工作流运行时写入契约，提供审批人交接和审批决定两个业务入口。
 * 实现负责核验办理权限、资源版本与幂等意图，并保持审批状态、审计和消息副作用一致。
 */
public interface WorkflowRuntimeService {
    /** 将尚未完成的审批级别交接给另一位合格审批人，并返回更新后的实例及幂等重放标记。 */
    ApproverChangeResult changeApprover(UUID instanceId, WorkflowDtos.ApproverChangeRequest request,
                                               String ifMatch, String idempotencyKey);

    /**
     * 由任务当前审批人提交通过或拒绝决定；通过 direction 区分动作，并检查 If-Match 与幂等键。
     * 返回决定后的任务快照；工作流推进及相应消息由实现与本次决定一并持久化。
     */
    DecisionResult decide(UUID approvalTaskId,
                                 WorkflowDtos.ApprovalDecisionRequest request,
                                 String direction, String ifMatch,
                                 String idempotencyKey);

    /** 审批交接结果，包含实例最新可见快照与请求是否为幂等重放的标识。 */
    public record ApproverChangeResult(WorkflowDtos.WorkflowInstanceResponse instance, boolean replayed) { }

    /** 审批决定结果，包含任务快照和是否复用既有决定的标识。 */
    public record DecisionResult(WorkflowDtos.ApprovalTaskResponse task, boolean replayed) {
    }
}
