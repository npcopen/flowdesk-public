package com.flowdesk.ticket.outbox;

import java.util.Map;
import java.util.Objects;

/** Exact RocketMQ wire message assembled from one persisted Ticket Outbox row. */
public record TicketOutboxMessage(
        String topic,
        String keys,
        byte[] body,
        Map<String, String> userProperties) {

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

    @Override
    public byte[] body() {
        return body.clone();
    }
}
