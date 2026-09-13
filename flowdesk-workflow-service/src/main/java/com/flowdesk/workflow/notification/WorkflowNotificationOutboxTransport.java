package com.flowdesk.workflow.notification;

@FunctionalInterface
public interface WorkflowNotificationOutboxTransport {
    WorkflowNotificationOutboxSendResult send(WorkflowNotificationOutboxMessage message)
            throws Exception;
}
