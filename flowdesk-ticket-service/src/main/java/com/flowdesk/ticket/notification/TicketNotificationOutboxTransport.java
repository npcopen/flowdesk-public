package com.flowdesk.ticket.notification;

/** 工单通知的同步传输端口，将可靠发布状态机与具体 Broker SDK 解耦。 */
@FunctionalInterface
public interface TicketNotificationOutboxTransport {
    /**
     * 发送已保存的发布内容，按传输确认结果推进或保留发件箱状态。
     */
    TicketNotificationOutboxSendResult send(TicketNotificationOutboxMessage message) throws Exception;
}
