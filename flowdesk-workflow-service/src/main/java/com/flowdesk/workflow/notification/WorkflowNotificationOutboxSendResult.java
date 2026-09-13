package com.flowdesk.workflow.notification;

import java.util.Objects;
import java.util.regex.Pattern;

/** Only SEND_OK is a broker confirmation; all other outcomes are retryable/failable. */
public record WorkflowNotificationOutboxSendResult(
        Status status, String errorCode, String safeErrorSummary) {
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,95}");

    public WorkflowNotificationOutboxSendResult {
        Objects.requireNonNull(status, "status");
        if (status == Status.SEND_OK) {
            if (errorCode != null || safeErrorSummary != null) {
                throw new IllegalArgumentException("SEND_OK must not carry an error");
            }
        }
        else if (errorCode == null || !CODE.matcher(errorCode).matches()) {
            throw new IllegalArgumentException("A stable error code is required");
        }
    }

    public static WorkflowNotificationOutboxSendResult sendOk() {
        return new WorkflowNotificationOutboxSendResult(Status.SEND_OK, null, null);
    }

    public static WorkflowNotificationOutboxSendResult notConfirmed(String code, String summary) {
        return new WorkflowNotificationOutboxSendResult(Status.NOT_CONFIRMED, code, summary);
    }

    public enum Status { SEND_OK, NOT_CONFIRMED }
}
