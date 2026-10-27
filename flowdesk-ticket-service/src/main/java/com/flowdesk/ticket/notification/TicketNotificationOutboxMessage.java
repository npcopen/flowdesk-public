package com.flowdesk.ticket.notification;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 从单条工单通知 Outbox 事实组装的 RocketMQ 消息，保留确定的载荷字节和属性。 */
public record TicketNotificationOutboxMessage(
        String topic, String keys, byte[] body, Map<String, String> userProperties) {
    /**
     * 保存记录字段，并对可变字节数组作防御性复制，避免调用方修改已构造的业务快照。
     */
    public TicketNotificationOutboxMessage {
        if (Objects.requireNonNull(topic, "topic").isBlank()) throw new IllegalArgumentException("topic must not be blank");
        if (Objects.requireNonNull(keys, "keys").isBlank()) throw new IllegalArgumentException("keys must not be blank");
        body = Objects.requireNonNull(body, "body").clone();
        userProperties = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(userProperties, "userProperties")));
    }
    /**
     * 返回消息原文字节的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] body() { return body.clone(); }
}
