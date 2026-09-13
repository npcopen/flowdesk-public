package com.flowdesk.workflow.outbox;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record WorkflowOutboxMessage(
        String topic, String keys, byte[] body, Map<String, String> userProperties) {

    public WorkflowOutboxMessage {
        body = body.clone();
        userProperties = Collections.unmodifiableMap(new LinkedHashMap<>(userProperties));
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
