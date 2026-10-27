package com.flowdesk.ticket.incident;

import java.util.Map;

/** Broker 原始 Incident 投递快照；复制载荷和属性，供契约校验使用。 */
public record IncidentTransportMessage(String topic,String consumerGroup,String keys,String tags,
                                       byte[] body,Map<String,String> userProperties){
    /**
     * 保存记录字段，并对可变字节数组作防御性复制，避免调用方修改已构造的业务快照。
     */
    public IncidentTransportMessage{body=body==null?null:body.clone();
        userProperties=userProperties==null?Map.of():Map.copyOf(userProperties);}
    /**
     * 返回消息原文字节的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] body(){return body==null?null:body.clone();}
}
