package com.flowdesk.notification.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 与客户端实现解耦的消息传输快照，对报文和属性作防御性复制。 */
public record NotificationTransportMessage(
        String topic, String consumerGroup, String keys, String tags,
        byte[] body, Map<String, String> userProperties) {
    /**
     * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
     */
    public NotificationTransportMessage {
        body = body == null ? null : body.clone();
        userProperties = userProperties == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(userProperties));
    }

    /**
     * 返回消息体字节的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override
    public byte[] body() {
        return body == null ? null : body.clone();
    }
}
