package com.flowdesk.ticket.event;

import com.flowdesk.ticket.service.IdempotencyCodec;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowStartV1ProducerContractTest {

    private static final String FIXTURE =
            "contracts/flowdesk-workflow-start-v1-wire.properties";

    @Test
    void productionCodecAndDerivedIntentMatchSharedConsumerFixtureExactly() throws Exception {
        Properties fixture = loadFixture();
        UUID tenantId = uuid(fixture, "fd_tenant_id");
        UUID ticketId = uuid(fixture, "fd_aggregate_id");

        byte[] actualBody = WorkflowStartV1PayloadCodec.encode(
                ticketId,
                UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db103"),
                UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db104"),
                3,
                UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db105"));

        assertThat(actualBody).containsExactly(
                Base64.getDecoder().decode(required(fixture, "body.base64")));
        assertThat(sha256Hex(actualBody)).isEqualTo(required(fixture, "fd_body_sha256"));
        assertThat(HexFormat.of().formatHex(IdempotencyCodec.derivedKey(
                TicketEventContract.WorkflowStartRequested.BUSINESS_INTENT_KEY_NAMESPACE,
                tenantId,
                ticketId)))
                .isEqualTo(required(fixture, "fd_business_intent_key"));

        assertThat(fixture)
                .containsEntry("fd_event_type",
                        TicketEventContract.WorkflowStartRequested.EVENT_TYPE)
                .containsEntry("fd_contract_ref",
                        TicketEventContract.WorkflowStartRequested.CONTRACT_REF)
                .containsEntry("fd_contract_version", Integer.toString(
                        TicketEventContract.WorkflowStartRequested.EVENT_VERSION))
                .containsEntry("fd_aggregate_type",
                        TicketEventContract.WorkflowStartRequested.AGGREGATE_TYPE);
    }

    private static Properties loadFixture() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = WorkflowStartV1ProducerContractTest.class
                .getClassLoader().getResourceAsStream(FIXTURE)) {
            if (input == null) {
                throw new IllegalStateException("Missing shared fixture " + FIXTURE);
            }
            properties.load(input);
        }
        return properties;
    }

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Fixture property is missing: " + name);
        }
        return value;
    }

    private static UUID uuid(Properties properties, String name) {
        return UUID.fromString(required(properties, name));
    }

    private static String sha256Hex(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
