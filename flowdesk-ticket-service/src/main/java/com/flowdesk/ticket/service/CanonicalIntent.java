package com.flowdesk.ticket.service;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.TreeMap;

/** Canonical JSON for idempotency intents; keys are sorted for a stable digest. */
public final class CanonicalIntent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CanonicalIntent() {
    }

    public static String of(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Intent fields must be key/value pairs");
        }
        Map<String, String> sorted = new TreeMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            sorted.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return MAPPER.writeValueAsString(sorted);
    }
}
