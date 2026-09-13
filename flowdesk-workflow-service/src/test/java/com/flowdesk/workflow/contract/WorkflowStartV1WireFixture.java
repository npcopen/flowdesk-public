package com.flowdesk.workflow.contract;

import com.flowdesk.workflow.start.WorkflowStartContract;
import com.flowdesk.workflow.start.WorkflowStartTransportMessage;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;

/** Test-only loader for the shared Ticket-produced wire fixture. */
final class WorkflowStartV1WireFixture {

    private static final String RESOURCE =
            "contracts/flowdesk-workflow-start-v1-wire.properties";

    private WorkflowStartV1WireFixture() {
    }

    static WireMessage load() {
        Properties source = new Properties();
        try (InputStream input = WorkflowStartV1WireFixture.class
                .getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing test fixture " + RESOURCE);
            }
            source.load(input);
        }
        catch (Exception ex) {
            throw new IllegalStateException("Cannot load test fixture " + RESOURCE, ex);
        }

        Map<String, String> userProperties = new HashMap<>();
        for (String name : source.stringPropertyNames()) {
            if (name.startsWith("fd_")) {
                userProperties.put(name, source.getProperty(name));
            }
        }
        return new WireMessage(
                required(source, "topic"),
                WorkflowStartContract.CONSUMER_GROUP,
                required(source, "keys"),
                null,
                Base64.getDecoder().decode(required(source, "body.base64")),
                userProperties);
    }

    private static String required(Properties source, String name) {
        String value = source.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Fixture property is missing: " + name);
        }
        return value;
    }

    record WireMessage(String topic,
                       String consumerGroup,
                       String keys,
                       String tags,
                       byte[] body,
                       Map<String, String> properties) {

        WireMessage {
            body = body == null ? null : Arrays.copyOf(body, body.length);
            properties = Map.copyOf(properties);
        }

        @Override
        public byte[] body() {
            return body == null ? null : Arrays.copyOf(body, body.length);
        }

        WorkflowStartTransportMessage transportMessage() {
            return new WorkflowStartTransportMessage(
                    topic, consumerGroup, keys, tags, body, properties);
        }

        WireMessage withProperty(String name, String value) {
            Map<String, String> changed = new HashMap<>(properties);
            changed.put(name, value);
            return new WireMessage(topic, consumerGroup, keys, tags, body, changed);
        }

        WireMessage withoutProperty(String name) {
            Map<String, String> changed = new HashMap<>(properties);
            changed.remove(name);
            return new WireMessage(topic, consumerGroup, keys, tags, body, changed);
        }

        WireMessage withBodyAndMatchingHash(byte[] changedBody) {
            Map<String, String> changed = new HashMap<>(properties);
            changed.put("fd_body_sha256", sha256Hex(changedBody));
            return new WireMessage(topic, consumerGroup, keys, tags, changedBody, changed);
        }

        WireMessage withMessageId(String changedMessageId) {
            Map<String, String> changed = new HashMap<>(properties);
            changed.put("fd_message_id", changedMessageId);
            return new WireMessage(
                    topic, consumerGroup, changedMessageId, tags, body, changed);
        }

        WireMessage withKeys(String changedKeys) {
            return new WireMessage(
                    topic, consumerGroup, changedKeys, tags, body, properties);
        }

        WireMessage withConsumerGroup(String changedGroup) {
            return new WireMessage(topic, changedGroup, keys, tags, body, properties);
        }

        WireMessage withTags(String changedTags) {
            return new WireMessage(
                    topic, consumerGroup, keys, changedTags, body, properties);
        }
    }

    static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
