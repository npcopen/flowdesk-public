package com.flowdesk.ticket.incident;

import java.util.Map;

record IncidentResultMessage(String topic, String key, byte[] body, Map<String, String> properties) {
    IncidentResultMessage {
        body = body.clone();
        properties = Map.copyOf(properties);
    }
    @Override public byte[] body() { return body.clone(); }
}
