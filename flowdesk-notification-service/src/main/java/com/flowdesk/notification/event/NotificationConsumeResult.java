package com.flowdesk.notification.event;

public record NotificationConsumeResult(Disposition disposition, String code) {
    public enum Disposition { ACK, RETRY }

    public static NotificationConsumeResult ack(String code) {
        return new NotificationConsumeResult(Disposition.ACK, code);
    }

    public static NotificationConsumeResult retry(String code) {
        return new NotificationConsumeResult(Disposition.RETRY, code);
    }
}
