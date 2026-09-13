package com.flowdesk.workflow.outbox;

public record WorkflowOutboxSendResult(Status status, String errorCode, String safeSummary) {
    public enum Status { SEND_OK, NOT_CONFIRMED }

    public static WorkflowOutboxSendResult sendOk() {
        return new WorkflowOutboxSendResult(Status.SEND_OK, null, null);
    }

    public static WorkflowOutboxSendResult notConfirmed(String code, String summary) {
        return new WorkflowOutboxSendResult(Status.NOT_CONFIRMED, code, summary);
    }
}
