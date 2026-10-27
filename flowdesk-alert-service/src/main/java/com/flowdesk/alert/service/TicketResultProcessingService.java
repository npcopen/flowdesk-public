package com.flowdesk.alert.service;

import com.flowdesk.alert.event.AcceptedTicketResult;

/** 工单结果业务裁决与死信恢复接纳；每个入口独立完成本地业务事务。 */
public interface TicketResultProcessingService {
    /**
     * 以同一本地事务处理已验证的工单结果，把故障关联、Inbox、恢复记录和审计一起提交。
     */
    void process(AcceptedTicketResult message);
    /**
     * 首次接纳死信时保存原消息、人工恢复记录及失败审计；已有消费裁决不会重新开放。
     */
    void acceptDeadLetter(AcceptedTicketResult message);
}
