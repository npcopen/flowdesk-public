package com.flowdesk.notification.event;

import com.flowdesk.common.notification.NotificationEventContract;

/** Notification-service view of the frozen shared bindings. */
public final class NotificationContract {
    public static final NotificationEventContract.Binding TICKET = NotificationEventContract.TICKET;
    public static final NotificationEventContract.Binding WORKFLOW = NotificationEventContract.WORKFLOW;
    public static final String PRODUCER_TICKET = TICKET.producerService();
    public static final String PRODUCER_WORKFLOW = WORKFLOW.producerService();
    public static final String AGGREGATE_TYPE = "NOTIFICATION_REQUEST";
    public static final String ENVELOPE_VERSION = NotificationEventContract.ENVELOPE_VERSION;
    public static final int VERSION = NotificationEventContract.VERSION;
    public static final int MAX_BODY_BYTES = NotificationEventContract.MAX_BODY_BYTES;
    public static final int MAX_RECONSUME_TIMES = NotificationEventContract.MAX_RECONSUME_TIMES;
    public static final int DELIVERY_ATTEMPTS = NotificationEventContract.DELIVERY_ATTEMPTS;
    public static final int DLQ_ACCEPTOR_MAX_RECONSUME_TIMES =
            NotificationEventContract.DLQ_ACCEPTOR_MAX_RECONSUME_TIMES;
    public static final int DLQ_ACCEPTOR_RETRY_DELAY_LEVEL =
            NotificationEventContract.DLQ_ACCEPTOR_RETRY_DELAY_LEVEL;

    private NotificationContract() {
    }

    public static NotificationEventContract.Binding[] bindings() {
        return NotificationEventContract.bindings();
    }
}
