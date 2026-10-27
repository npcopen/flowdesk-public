package com.flowdesk.workflow.notification;

/**
 * 审批通知发送端口，使发布策略独立于具体消息中间件实现。
 */
@FunctionalInterface
public interface WorkflowNotificationOutboxTransport {
    /**
     * 发送当前消息并返回代理确认结果，重复发送保留原身份与载荷。
     */
    WorkflowNotificationOutboxSendResult send(WorkflowNotificationOutboxMessage message)
            throws Exception;
}
