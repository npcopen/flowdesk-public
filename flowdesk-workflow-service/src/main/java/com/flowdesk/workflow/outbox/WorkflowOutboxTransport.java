package com.flowdesk.workflow.outbox;

@FunctionalInterface
public interface WorkflowOutboxTransport {
    WorkflowOutboxSendResult send(WorkflowOutboxMessage message) throws Exception;
}
