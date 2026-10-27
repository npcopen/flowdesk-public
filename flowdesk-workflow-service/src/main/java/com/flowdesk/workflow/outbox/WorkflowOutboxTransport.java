package com.flowdesk.workflow.outbox;

/**
 * 工作流反向事件的发送端口，供两个事件契约共用发布流程。
 */
@FunctionalInterface
public interface WorkflowOutboxTransport {
    /**
     * 发送当前消息并返回代理确认结果，重复发送保留原身份与载荷。
     */
    WorkflowOutboxSendResult send(WorkflowOutboxMessage message) throws Exception;
}
