package com.flowdesk.iam.service;


import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.TreeMap;

/**
 * Shared canonical JSON representation for idempotency intents. Key order is sorted so
 * semantically identical inputs always produce the same digest.
 */
final class CanonicalIntent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CanonicalIntent() {
    }

    static String of(String... keyValuePairs) {
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
