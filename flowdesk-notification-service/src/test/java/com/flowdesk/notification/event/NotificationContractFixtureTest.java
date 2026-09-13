package com.flowdesk.notification.event;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.notification.NotificationWireCodec;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** Frozen M8 Notification wire fixtures exercised through the consumer guard. */
class NotificationContractFixtureTest {
    private static final NotificationEnvelopeGuard GUARD = new NotificationEnvelopeGuard();
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RECIPIENT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SOURCE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CORRELATION = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-30T00:00:00.000000Z");

    @Test
    void frozenFixturesMatchSharedBindingsAndAcceptCanonicalDeliveries() {
        for (String resource : fixtures()) {
            Properties fixture = fixture(resource);
            NotificationEventContract.Binding binding = binding(fixture);
            FixtureDelivery delivery = delivery(binding, "ASSIGNMENT_NOTICE", "通知正文");

            assertThat(fixture.getProperty("eventType")).isEqualTo(binding.eventType());
            assertThat(fixture.getProperty("contractRef")).isEqualTo(binding.contractRef());
            assertThat(fixture.getProperty("topic")).isEqualTo(binding.topic());
            assertThat(fixture.getProperty("producer")).isEqualTo(binding.producerService());
            assertThat(fixture.getProperty("producerGroup")).isEqualTo(binding.producerGroup());
            assertThat(fixture.getProperty("consumerGroup")).isEqualTo(binding.consumerGroup());
            assertThat(fixture.getProperty("dlqAcceptorGroup")).isEqualTo(binding.dlqAcceptorGroup());
            assertThat(fixture.getProperty("aggregateType")).isEqualTo("NOTIFICATION_REQUEST");
            assertThat(fixture.getProperty("messageKey")).isEqualTo("fd_message_id");
            assertThat(fixture.getProperty("bodyMaxBytes")).isEqualTo("65536");

            AcceptedNotification accepted = GUARD.validateMain(delivery.message());
            assertThat(accepted.binding()).isEqualTo(binding);
            assertThat(accepted.tenantId()).isEqualTo(TENANT);
            assertThat(accepted.messageId()).isEqualTo(delivery.messageId());
            assertThat(accepted.messageKey()).isEqualTo(delivery.messageId().toString());
            assertThat(accepted.aggregateId()).isEqualTo(SOURCE);
            assertThat(accepted.recipientUserId()).isEqualTo(RECIPIENT);
            assertThat(accepted.notificationKind()).isEqualTo("ASSIGNMENT_NOTICE");
            assertThat(accepted.bodySha256()).isEqualTo(NotificationWireCodec.sha256(delivery.body()));

            NotificationTransportMessage dlq = new NotificationTransportMessage(
                    binding.dlqTopic(), binding.dlqAcceptorGroup(), delivery.messageId().toString(),
                    null, delivery.body(), delivery.properties());
            assertThat(GUARD.validateDlq(dlq).messageId()).isEqualTo(delivery.messageId());
        }
    }

    @Test
    void guardRejectsFrozenWireViolationsBeforeTenantTrust() {
        FixtureDelivery delivery = delivery(NotificationEventContract.TICKET,
                "ASSIGNMENT_NOTICE", "通知正文");

        assertViolation(new NotificationTransportMessage(
                delivery.message().topic(), delivery.message().consumerGroup(),
                delivery.messageId().toString(), null, delivery.body(),
                with(delivery.properties(), "fd_producer", "flowdesk-workflow-service")),
                "ENVELOPE_VALUE_INVALID:fd_producer",
                NotificationContractViolation.TrustBoundary.PRE_TRUST);
        assertViolation(new NotificationTransportMessage(
                NotificationEventContract.TICKET.dlqTopic(),
                NotificationEventContract.TICKET.consumerGroup(), delivery.messageId().toString(),
                null, delivery.body(), delivery.properties()), "CONSUMER_DIRECTION_MISMATCH",
                NotificationContractViolation.TrustBoundary.PRE_TRUST);
        assertViolation(new NotificationTransportMessage(
                delivery.message().topic(), delivery.message().consumerGroup(),
                delivery.messageId().toString(), null, delivery.body(),
                with(delivery.properties(), "fd_body_sha256", "0".repeat(64))), "BODY_HASH_MISMATCH",
                NotificationContractViolation.TrustBoundary.PRE_TRUST);
        assertViolation(new NotificationTransportMessage(
                delivery.message().topic(), delivery.message().consumerGroup(),
                delivery.messageId().toString(), null, delivery.body(),
                with(delivery.properties(), "fd_unknown", "forbidden")),
                "UNKNOWN_ENVELOPE_PROPERTY:fd_unknown",
                NotificationContractViolation.TrustBoundary.PRE_TRUST);

        byte[] unknownPayload = (new String(delivery.body(), StandardCharsets.UTF_8)
                .replace("}", ",\"unexpected\":\"field\"}"))
                .getBytes(StandardCharsets.UTF_8);
        assertViolation(new NotificationTransportMessage(
                delivery.message().topic(), delivery.message().consumerGroup(),
                delivery.messageId().toString(), null, unknownPayload,
                with(delivery.properties(), "fd_body_sha256", NotificationWireCodec.hex(
                        NotificationWireCodec.sha256(unknownPayload)))),
                "PAYLOAD_UNKNOWN_FIELD:unexpected",
                NotificationContractViolation.TrustBoundary.PRE_TRUST);

        assertViolation(new NotificationTransportMessage(
                delivery.message().topic(), delivery.message().consumerGroup(),
                delivery.messageId().toString(), null, delivery.body(),
                with(delivery.properties(), "fd_tenant_id",
                        "55555555-5555-5555-5555-555555555555")),
                "BUSINESS_INTENT_KEY_MISMATCH",
                NotificationContractViolation.TrustBoundary.TRUSTED_TENANT);
    }

    private static List<String> fixtures() {
        return List.of("contracts/flowdesk-ticket-notification-requested-v1-wire.properties",
                "contracts/flowdesk-workflow-notification-requested-v1-wire.properties");
    }

    private static Properties fixture(String resource) {
        Properties result = new Properties();
        try (InputStream stream = NotificationContractFixtureTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing Notification fixture: " + resource);
            }
            result.load(stream);
            return result;
        }
        catch (Exception failure) {
            throw new IllegalStateException("Unable to load Notification fixture: " + resource, failure);
        }
    }

    private static NotificationEventContract.Binding binding(Properties fixture) {
        return java.util.Arrays.stream(NotificationEventContract.bindings())
                .filter(binding -> binding.contractRef().equals(fixture.getProperty("contractRef")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown fixture binding"));
    }

    private static FixtureDelivery delivery(NotificationEventContract.Binding binding,
                                            String kind, String title) {
        byte[] sourceEffect = NotificationWireCodec.deriveKey("FD-M8-FIXTURE-SOURCE-V1",
                binding.contractRef());
        NotificationWireCodec.Payload payload = new NotificationWireCodec.Payload("1", RECIPIENT,
                kind, NotificationWireCodec.hex(sourceEffect), "TICKET", SOURCE, title, "正文");
        byte[] body = NotificationWireCodec.encodePayload(payload);
        byte[] intent = NotificationWireCodec.deriveKey("FD-NOTIFICATION-INTENT-V1", TENANT,
                sourceEffect, RECIPIENT, kind);
        byte[] publication = NotificationWireCodec.deriveKey("FD-NOTIFICATION-PUBLICATION-V1",
                TENANT, sourceEffect, RECIPIENT, kind);
        UUID messageId = NotificationWireCodec.uuidFromKey(publication);
        Map<String, String> properties = NotificationWireCodec.envelope(binding, TENANT, messageId,
                intent, publication, SOURCE, CORRELATION, null, OCCURRED_AT, body);
        return new FixtureDelivery(messageId, body, properties,
                new NotificationTransportMessage(binding.topic(), binding.consumerGroup(),
                        messageId.toString(), null, body, properties));
    }

    private static Map<String, String> with(Map<String, String> source, String key, String value) {
        Map<String, String> copy = new LinkedHashMap<>(source);
        copy.put(key, value);
        return copy;
    }

    private static Violation violation(NotificationTransportMessage message) {
        try {
            GUARD.validateMain(message);
            fail("Expected Notification wire violation");
            throw new AssertionError("unreachable");
        }
        catch (NotificationContractViolation expected) {
            return new Violation(expected.code(), expected.boundary());
        }
    }

    private static void assertViolation(NotificationTransportMessage message, String code,
                                        NotificationContractViolation.TrustBoundary boundary) {
        Violation violation = violation(message);
        assertThat(violation.code()).isEqualTo(code);
        assertThat(violation.boundary()).isEqualTo(boundary);
    }

    private record FixtureDelivery(UUID messageId, byte[] body, Map<String, String> properties,
                                   NotificationTransportMessage message) { }
    private record Violation(String code, NotificationContractViolation.TrustBoundary boundary) { }
}
