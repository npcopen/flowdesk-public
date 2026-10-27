package com.flowdesk.workflow.service.start;
import com.flowdesk.workflow.start.AcceptedWorkflowStart;
import com.flowdesk.workflow.model.start.WorkflowStartModels.Preflight;
import com.flowdesk.workflow.model.start.WorkflowStartModels.Completion;
/**
 * 工作流启动写入边界，在代理确认前原子提交 Inbox、实例、任务、消息意图和审计。
 */
public interface WorkflowStartCommandService { /**
     * 根据可信消息及事务外预检提交本地决定，相关事实原子落库后才允许确认投递。
     */
    Completion complete(AcceptedWorkflowStart message, Preflight preflight); }
