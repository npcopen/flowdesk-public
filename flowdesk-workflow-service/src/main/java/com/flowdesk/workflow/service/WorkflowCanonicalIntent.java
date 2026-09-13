package com.flowdesk.workflow.service;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.TreeMap;

/** Small canonical JSON encoder for persisted idempotency audit anchors. */
final class WorkflowCanonicalIntent {

    private static final ObjectMapper JSON = new ObjectMapper();

    private WorkflowCanonicalIntent() {
    }

    static String of(String... fields) {
        if (fields.length % 2 != 0) {
            throw new IllegalArgumentException("Intent fields must be key/value pairs");
        }
        Map<String, String> sorted = new TreeMap<>();
        for (int index = 0; index < fields.length; index += 2) {
            sorted.put(fields[index], fields[index + 1]);
        }
        return JSON.writeValueAsString(sorted);
    }
}
