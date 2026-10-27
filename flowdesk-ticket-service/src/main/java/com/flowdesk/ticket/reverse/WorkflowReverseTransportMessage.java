package com.flowdesk.ticket.reverse;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 流程反向事件的 Broker 原始投递快照，复制载荷与用户属性以保护校验输入。 */
public record WorkflowReverseTransportMessage(
        String topic, String consumerGroup, String keys, String tags,
        byte[] body, Map<String, String> userProperties) {
    /**
     * 保存记录字段，并对可变字节数组作防御性复制，避免调用方修改已构造的业务快照。
     */
    public WorkflowReverseTransportMessage {
        body = body == null ? null : body.clone();
        userProperties = userProperties == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(userProperties));
    }
    /**
     * 返回消息原文字节的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] body() { return body == null ? null : body.clone(); }
}
