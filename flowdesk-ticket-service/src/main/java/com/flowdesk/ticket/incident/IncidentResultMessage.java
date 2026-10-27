package com.flowdesk.ticket.incident;

import java.util.Map;

/** 发送给告警服务的 Incident 建单结果消息，封装主题、消息键、原始载荷及受保护属性。 */
record IncidentResultMessage(String topic, String key, byte[] body, Map<String, String> properties) {
    /**
     * 保存记录字段，并对可变字节数组作防御性复制，避免调用方修改已构造的业务快照。
     */
    IncidentResultMessage {
        body = body.clone();
        properties = Map.copyOf(properties);
    }
    /**
     * 返回消息原文字节的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] body() { return body.clone(); }
}
