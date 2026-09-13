package com.flowdesk.workflow.start;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Broker delivery presented to the strict Workflow-start entry.
 *
 * <p>The later RocketMQ binding is responsible for supplying its configured consumer
 * group here. This slice deliberately does not activate that binding.</p>
 */
public record WorkflowStartTransportMessage(
        String topic,
        String consumerGroup,
        String keys,
        String tags,
        byte[] body,
        Map<String, String> userProperties) {

    public WorkflowStartTransportMessage {
        body = body == null ? null : body.clone();
        userProperties = userProperties == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(userProperties));
    }

    @Override
    public byte[] body() {
        return body == null ? null : body.clone();
    }
}
