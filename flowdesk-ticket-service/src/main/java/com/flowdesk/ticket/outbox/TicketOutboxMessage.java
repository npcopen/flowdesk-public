package com.flowdesk.ticket.outbox;

import java.util.Map;
import java.util.Objects;

/** 从单条持久化工单 Outbox 事实组装的 RocketMQ 线消息，保持载荷字节与契约属性一致。 */
public record TicketOutboxMessage(
        String topic,
        String keys,
        byte[] body,
        Map<String, String> userProperties) {

    /**
     * 保存记录字段，并对可变字节数组作防御性复制，避免调用方修改已构造的业务快照。
     */
    public TicketOutboxMessage {
        if (Objects.requireNonNull(topic, "topic").isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        if (Objects.requireNonNull(keys, "keys").isBlank()) {
            throw new IllegalArgumentException("keys must not be blank");
        }
        body = Objects.requireNonNull(body, "body").clone();
        userProperties = Map.copyOf(Objects.requireNonNull(userProperties, "userProperties"));
    }

    /**
     * 返回消息原文字节的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override
    public byte[] body() {
        return body.clone();
    }
}
