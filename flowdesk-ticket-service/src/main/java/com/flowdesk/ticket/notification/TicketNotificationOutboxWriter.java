package com.flowdesk.ticket.notification;

import java.util.UUID;

/** 在调用方业务事务内持久化通知意图。 */
public interface TicketNotificationOutboxWriter {
    /**
     * 保存业务发布意图，由持久化约束保证去重与内容一致。
     */
    void write(UUID tenantId, TicketNotificationIntent.Intent intent);
}
