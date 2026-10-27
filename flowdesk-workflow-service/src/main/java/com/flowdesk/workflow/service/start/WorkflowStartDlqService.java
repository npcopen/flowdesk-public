package com.flowdesk.workflow.service.start;
import com.flowdesk.workflow.start.AcceptedWorkflowStart;
import com.flowdesk.workflow.start.WorkflowStartConsumeResult;
/**
 * 工作流死信接纳边界，在短事务保存可恢复原消息及恢复事实，或记录确定性拒绝。
 */
public interface WorkflowStartDlqService { /**
     * 持久化可信启动死信的可恢复来源或确定性拒绝，返回传输处置结果。
     */
    WorkflowStartConsumeResult accept(AcceptedWorkflowStart message); }
