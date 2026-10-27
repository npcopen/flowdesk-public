package com.flowdesk.ticket.incident;

import java.time.Instant;
import java.util.UUID;

/** 通过传输与载荷校验后的 Incident 消息事实；防御性复制字节数组，防止校验后被外部修改。 */
public record AcceptedIncidentMessage(IncidentMessageContract.Binding binding,UUID tenantId,
        UUID messageId,byte[] businessIntentKey,UUID incidentId,byte[] createTicketIntentKey,
        String fingerprint,Integer generationNo,String cycleRef,String summary,Instant openedAt,
        UUID recoveryEventId,Instant recoveredAt,UUID correlationId,UUID causationId,
        Instant envelopeOccurredAt,byte[] bodyBytes,byte[] bodySha256){
    /**
     * 保存记录字段，并对可变字节数组作防御性复制，避免调用方修改已构造的业务快照。
     */
    public AcceptedIncidentMessage{businessIntentKey=businessIntentKey.clone();
        createTicketIntentKey=createTicketIntentKey.clone();bodyBytes=bodyBytes.clone();bodySha256=bodySha256.clone();}
    /**
     * 返回业务意图键的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] businessIntentKey(){return businessIntentKey.clone();}
    /**
     * 返回createTicketIntentKey的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] createTicketIntentKey(){return createTicketIntentKey.clone();}
    /**
     * 返回消息原文字节的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] bodyBytes(){return bodyBytes.clone();}
    /**
     * 返回消息 SHA-256 摘要的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] bodySha256(){return bodySha256.clone();}
}
