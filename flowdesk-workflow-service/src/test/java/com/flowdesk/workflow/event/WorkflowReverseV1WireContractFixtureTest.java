package com.flowdesk.workflow.event;

import com.flowdesk.workflow.service.WorkflowIdempotencyCodec;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowReverseV1WireContractFixtureTest {
    private static final String ESTABLISHED =
            "contracts/flowdesk-workflow-instance-established-v1-wire.properties";
    private static final String FINALIZED =
            "contracts/flowdesk-workflow-approval-finalized-v1-wire.properties";

    @Test
    void instanceEstablishedCodecAndBindingMatchTheSharedTicketFixture() throws Exception {
        Properties fixture = load(ESTABLISHED);
        byte[] body = WorkflowReverseEventCodec.instanceEstablished(
                UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db102"),
                UUID.fromString(required(fixture, "fd_aggregate_id")),
                HexFormat.of().parseHex("bc5fde73c2ba4fe03c74a1ed19c984504d39e979c08eb8667599920f4b731c1a"),
                UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db103"),
                UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db104"), 3,
                "RUNNING", Instant.parse("2026-08-24T01:02:03.123456Z"));

        assertFixture(body, fixture, WorkflowReverseEventCodec.instanceEstablishedIntent(
                UUID.fromString(required(fixture, "fd_tenant_id")),
                UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db102")));
        assertBinding(fixture, WorkflowReverseEventContract.InstanceEstablished.TOPIC,
                WorkflowReverseEventContract.InstanceEstablished.EVENT_TYPE,
                WorkflowReverseEventContract.InstanceEstablished.CONTRACT_REF,
                WorkflowReverseEventContract.InstanceEstablished.PRODUCER_GROUP,
                WorkflowReverseEventContract.InstanceEstablished.CONSUMER_GROUP,
                WorkflowReverseEventContract.InstanceEstablished.DLQ_ACCEPTOR_GROUP);
    }

    @Test
    void approvalFinalizedCodecAndBindingMatchTheSharedTicketFixture() throws Exception {
        Properties fixture = load(FINALIZED);
        UUID instanceId = UUID.fromString(required(fixture, "fd_aggregate_id"));
        byte[] body = WorkflowReverseEventCodec.approvalFinalized(
                UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db102"), instanceId,
                HexFormat.of().parseHex("bc5fde73c2ba4fe03c74a1ed19c984504d39e979c08eb8667599920f4b731c1a"),
                UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db103"),
                UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db104"), 3,
                "PASSED", Instant.parse("2026-08-24T02:03:04.654321Z"));

        assertFixture(body, fixture, WorkflowReverseEventCodec.approvalFinalizedIntent(
                UUID.fromString(required(fixture, "fd_tenant_id")), instanceId));
        assertBinding(fixture, WorkflowReverseEventContract.ApprovalFinalized.TOPIC,
                WorkflowReverseEventContract.ApprovalFinalized.EVENT_TYPE,
                WorkflowReverseEventContract.ApprovalFinalized.CONTRACT_REF,
                WorkflowReverseEventContract.ApprovalFinalized.PRODUCER_GROUP,
                WorkflowReverseEventContract.ApprovalFinalized.CONSUMER_GROUP,
                WorkflowReverseEventContract.ApprovalFinalized.DLQ_ACCEPTOR_GROUP);
    }

    @Test
    void reverseTransportBoundariesRemainExact() {
        assertThat(WorkflowReverseEventContract.SEND_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
        assertThat(WorkflowReverseEventContract.SDK_SEND_RETRIES).isZero();
        assertThat(WorkflowReverseEventContract.OUTBOX_CLAIM_LEASE)
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(WorkflowReverseEventContract.OUTBOX_MAX_CLAIMS).isEqualTo(50);
        assertThat(WorkflowReverseEventContract.OUTBOX_MAX_ATTEMPTS).isEqualTo(8);
        assertThat(WorkflowReverseEventContract.MAX_RECONSUME_TIMES).isEqualTo(5);
        assertThat(WorkflowReverseEventContract.FAILURE_BACKOFFS).containsExactlyElementsOf(List.of(
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30),
                Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofMinutes(30),
                Duration.ofHours(2)));
    }

    private static void assertFixture(byte[] body, Properties fixture, byte[] intent)
            throws Exception {
        assertThat(body).containsExactly(Base64.getDecoder().decode(required(fixture, "body.base64")));
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)))
                .isEqualTo(required(fixture, "fd_body_sha256"));
        assertThat(HexFormat.of().formatHex(intent))
                .isEqualTo(required(fixture, "fd_business_intent_key"));
        assertThat(WorkflowIdempotencyCodec.publicationKey(
                UUID.fromString(required(fixture, "fd_tenant_id")),
                UUID.fromString(required(fixture, "fd_message_id"))))
                .hasSize(32);
    }

    private static void assertBinding(Properties fixture, String topic, String event,
                                      String contract, String producerGroup,
                                      String consumerGroup, String acceptorGroup) {
        assertThat(fixture).containsEntry("topic", topic)
                .containsEntry("fd_event_type", event)
                .containsEntry("fd_contract_ref", contract)
                .containsEntry("fd_contract_version", "1")
                .containsEntry("fd_producer", WorkflowReverseEventContract.PRODUCER_SERVICE)
                .containsEntry("fd_aggregate_type", WorkflowReverseEventContract.AGGREGATE_TYPE)
                .containsEntry("producer.group", producerGroup)
                .containsEntry("consumer.group", consumerGroup)
                .containsEntry("dlq.acceptor.group", acceptorGroup);
    }

    private static Properties load(String path) throws Exception {
        Properties value = new Properties();
        try (InputStream input = WorkflowReverseV1WireContractFixtureTest.class
                .getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing shared fixture " + path);
            value.load(input);
        }
        return value;
    }
    private static String required(Properties fixture, String name) {
        String value = fixture.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name);
        return value;
    }
}
