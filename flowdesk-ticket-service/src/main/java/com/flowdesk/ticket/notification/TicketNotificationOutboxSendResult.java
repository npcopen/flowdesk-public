package com.flowdesk.ticket.notification;

import java.util.Objects;
import java.util.regex.Pattern;

public record TicketNotificationOutboxSendResult(Status status, String errorCode,
                                                 String safeErrorSummary) {
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,95}");
    public TicketNotificationOutboxSendResult {
        Objects.requireNonNull(status, "status");
        if (status == Status.SEND_OK) {
            if (errorCode != null || safeErrorSummary != null) throw new IllegalArgumentException("SEND_OK must not carry an error");
        } else if (errorCode == null || !CODE.matcher(errorCode).matches()) {
            throw new IllegalArgumentException("A stable error code is required");
        }
    }
    public static TicketNotificationOutboxSendResult sendOk() { return new TicketNotificationOutboxSendResult(Status.SEND_OK, null, null); }
    public static TicketNotificationOutboxSendResult notConfirmed(String code, String summary) { return new TicketNotificationOutboxSendResult(Status.NOT_CONFIRMED, code, summary); }
    public enum Status { SEND_OK, NOT_CONFIRMED }
}
