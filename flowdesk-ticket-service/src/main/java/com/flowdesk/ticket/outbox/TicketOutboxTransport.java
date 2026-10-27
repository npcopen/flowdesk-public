package com.flowdesk.ticket.outbox;

import com.flowdesk.ticket.event.TicketEventContract;

/**
 * 同步工单 Outbox 传输端口。
 * 适配器必须采用 {@link TicketEventContract.WorkflowStartRequested} 的固定生产者组、超时及零 SDK 重试；
 * 正常返回不等于确认，只有 RocketMQ SEND_OK 才可映射为 SEND_OK。
 */
@FunctionalInterface
public interface TicketOutboxTransport {

    /**
     * 发送已保存的发布内容，按传输确认结果推进或保留发件箱状态。
     */
    TicketOutboxSendResult send(TicketOutboxMessage message) throws Exception;
}
