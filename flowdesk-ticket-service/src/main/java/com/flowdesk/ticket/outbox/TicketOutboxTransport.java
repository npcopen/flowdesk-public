package com.flowdesk.ticket.outbox;

import com.flowdesk.ticket.event.TicketEventContract;

/**
 * Synchronous Ticket Outbox transport boundary.
 *
 * <p>The RocketMQ adapter must use the frozen producer group, timeout and zero SDK retries from
 * {@link TicketEventContract.WorkflowStartRequested}. Returning normally is not sufficient for
 * confirmation: the adapter must return {@code SEND_OK} only for RocketMQ {@code SEND_OK}.</p>
 */
@FunctionalInterface
public interface TicketOutboxTransport {

    TicketOutboxSendResult send(TicketOutboxMessage message) throws Exception;
}
