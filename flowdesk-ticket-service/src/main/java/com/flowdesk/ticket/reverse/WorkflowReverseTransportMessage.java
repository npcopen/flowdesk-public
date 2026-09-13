package com.flowdesk.ticket.reverse;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record WorkflowReverseTransportMessage(
        String topic, String consumerGroup, String keys, String tags,
        byte[] body, Map<String, String> userProperties) {
    public WorkflowReverseTransportMessage {
        body = body == null ? null : body.clone();
        userProperties = userProperties == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(userProperties));
    }
    @Override public byte[] body() { return body == null ? null : body.clone(); }
}
