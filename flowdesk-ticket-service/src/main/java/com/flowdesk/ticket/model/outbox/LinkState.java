package com.flowdesk.ticket.model.outbox;

/**
 * 工单启动关联状态，供发件箱结果结算核对业务进度。
 */
public record LinkState(String status, long lockVersion) {
}
