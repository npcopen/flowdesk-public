package com.flowdesk.alert.webhook;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Loads ignored, runtime-only HMAC material without ever exposing values. */
public final class WebhookSecretRegistry {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final byte[] dummySecret;
    private final Map<UUID, byte[]> integrationSecrets;

    public WebhookSecretRegistry(WebhookProperties properties) {
        properties.validateEnabled();
        try {
            JsonNode root = JSON.readTree(Files.readAllBytes(Path.of(properties.getSecretsFile())));
            if (root == null || !root.isObject() || root.size() != 2
                    || root.get("dummySecret") == null || !root.get("dummySecret").isString()
                    || root.get("integrations") == null || !root.get("integrations").isObject()) {
                throw invalid();
            }
            dummySecret = decode(root.get("dummySecret").stringValue());
            Map<UUID, byte[]> values = new HashMap<>();
            var fields = root.get("integrations").properties();
            for (var entry : fields) {
                UUID id = UUID.fromString(entry.getKey());
                if (!id.toString().equals(entry.getKey()) || !entry.getValue().isString()
                        || values.put(id, decode(entry.getValue().stringValue())) != null) {
                    throw invalid();
                }
            }
            integrationSecrets = Map.copyOf(values);
        }
        catch (Exception failure) {
            if (failure instanceof IllegalStateException state) throw state;
            throw invalid();
        }
    }

    public byte[] dummySecret() { return dummySecret.clone(); }
    public Optional<byte[]> secret(UUID integrationId) {
        byte[] value = integrationSecrets.get(integrationId);
        return value == null ? Optional.empty() : Optional.of(value.clone());
    }

    private static byte[] decode(String encoded) {
        byte[] value = Base64.getDecoder().decode(encoded);
        if (value.length < 32) throw invalid();
        return value;
    }
    private static IllegalStateException invalid() {
        return new IllegalStateException("Alert Webhook secret registry is invalid");
    }
}
