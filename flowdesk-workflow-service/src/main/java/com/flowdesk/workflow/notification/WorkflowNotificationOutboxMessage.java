package com.flowdesk.workflow.notification;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Exact RocketMQ wire message assembled from one persisted notification Outbox row. */
public record WorkflowNotificationOutboxMessage(
        String topic, String keys, byte[] body, Map<String, String> userProperties) {
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

    @Override public byte[] body() { return body.clone(); }
}
