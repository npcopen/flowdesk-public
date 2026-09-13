package com.flowdesk.notification.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Transport-neutral RocketMQ delivery used by the guard and focused adapters. */
public record NotificationTransportMessage(
        String topic, String consumerGroup, String keys, String tags,
        byte[] body, Map<String, String> userProperties) {
    public NotificationTransportMessage {
        body = body == null ? null : body.clone();
        userProperties = userProperties == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(userProperties));
    }

    @Override
    public byte[] body() {
        return body == null ? null : body.clone();
    }
}
