package com.flowdesk.workflow.notification;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 由通知 Outbox 持久化事实组装的 RocketMQ 消息，包括精确字节和信封属性。
 */
public record WorkflowNotificationOutboxMessage(
        String topic, String keys, byte[] body, Map<String, String> userProperties) {
    /**
     * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
     */
    public WorkflowNotificationOutboxMessage {
        if (Objects.requireNonNull(topic, "topic").isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        if (Objects.requireNonNull(keys, "keys").isBlank()) {
            throw new IllegalArgumentException("keys must not be blank");
        }
        body = Objects.requireNonNull(body, "body").clone();
        userProperties = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(userProperties, "userProperties")));
    }

    /**
     * 返回消息体字节的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override public byte[] body() { return body.clone(); }
}
