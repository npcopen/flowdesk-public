package com.flowdesk.alert.webhook;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict SCHEMA-041 parser over the already authenticated original bytes. */
public record WebhookAlertPayload(String eventId, String eventType, String fingerprint,
                                  String cycleRef, Instant occurredAt, String summary) {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final Set<String> FIELDS = Set.of(
            "eventId", "eventType", "fingerprint", "cycleRef", "occurredAt", "summary");
    private static final Pattern UTC_MICROS = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z");

    public static WebhookAlertPayload parse(byte[] body) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
            JsonNode root;
            try (var parser = JSON.createParser(text)) {
                root = JSON.readTree(parser);
                require(parser.nextToken() == null);
            }
            require(root != null && root.isObject());
            Set<String> actual = new java.util.HashSet<>();
            root.properties().forEach(e -> actual.add(e.getKey()));
            require(actual.equals(FIELDS));
            String eventId = requiredText(root, "eventId", 1, 128);
            String eventType = requiredText(root, "eventType", 1, 16);
            require("FIRING".equals(eventType) || "RESOLVED".equals(eventType));
            String fingerprint = requiredText(root, "fingerprint", 1, 255);
            String cycleRef = requiredText(root, "cycleRef", 1, 128);
            String occurredText = requiredText(root, "occurredAt", 1, 64);
            require(UTC_MICROS.matcher(occurredText).matches());
            Instant occurredAt = Instant.parse(occurredText);
            JsonNode summaryNode = root.get("summary");
            String summary = null;
            if (!summaryNode.isNull()) {
                require(summaryNode.isString());
                summary = summaryNode.stringValue();
                require(validScalarText(summary, 0, 1000));
            }
            return new WebhookAlertPayload(eventId, eventType, fingerprint,
                    cycleRef, occurredAt, summary);
        }
        catch (InvalidPayload invalid) { throw invalid; }
        catch (Exception invalid) { throw new InvalidPayload(); }
    }

    private static String requiredText(JsonNode root, String field, int min, int max) {
        JsonNode value = root.get(field); require(value != null && value.isString());
        String text = value.stringValue(); require(validScalarText(text, min, max)); return text;
    }
    private static boolean validScalarText(String value, int min, int max) {
        int count = value.codePointCount(0, value.length());
        if (count < min || count > max) return false;
        for (int i = 0; i < value.length();) {
            char c = value.charAt(i);
            if (Character.isSurrogate(c)) {
                if (!Character.isHighSurrogate(c) || i + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(i + 1))) return false;
                i += 2; continue;
            }
            if (c != '\t' && c != '\n' && c != '\r' && Character.isISOControl(c)) return false;
            i++;
        }
        return true;
    }
    private static void require(boolean condition) { if (!condition) throw new InvalidPayload(); }
    public static final class InvalidPayload extends RuntimeException { }
}
