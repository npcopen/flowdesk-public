package com.flowdesk.ticket.outbox;

import java.util.Objects;
import java.util.regex.Pattern;

/** Transport result. Only {@link Status#SEND_OK} is a Broker confirmation. */
public record TicketOutboxSendResult(Status status, String errorCode, String safeErrorSummary) {

    private static final Pattern STABLE_ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,95}");

    public TicketOutboxSendResult {
        Objects.requireNonNull(status, "status");
        if (status == Status.SEND_OK) {
            if (errorCode != null || safeErrorSummary != null) {
                throw new IllegalArgumentException("SEND_OK must not carry an error");
            }
        }
        else if (errorCode == null || !STABLE_ERROR_CODE.matcher(errorCode).matches()) {
            throw new IllegalArgumentException("A stable error code is required when send is not confirmed");
        }
    }

    public static TicketOutboxSendResult sendOk() {
        return new TicketOutboxSendResult(Status.SEND_OK, null, null);
    }

    public static TicketOutboxSendResult notConfirmed(String errorCode, String safeErrorSummary) {
        return new TicketOutboxSendResult(Status.NOT_CONFIRMED, errorCode, safeErrorSummary);
    }

    public enum Status {
        SEND_OK,
        NOT_CONFIRMED
    }
}
