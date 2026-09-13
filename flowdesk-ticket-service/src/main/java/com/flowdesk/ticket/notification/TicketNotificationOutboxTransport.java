package com.flowdesk.ticket.notification;

@FunctionalInterface
public interface TicketNotificationOutboxTransport {
    TicketNotificationOutboxSendResult send(TicketNotificationOutboxMessage message) throws Exception;
}
