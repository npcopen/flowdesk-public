package com.flowdesk.workflow.contract;

import com.flowdesk.workflow.start.AcceptedWorkflowStart;
import com.flowdesk.workflow.start.WorkflowStartContractViolation;
import com.flowdesk.workflow.start.WorkflowStartEnvelopeGuard;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowStartV1WireContractFixtureTest {

    private static final WorkflowStartEnvelopeGuard GUARD = new WorkflowStartEnvelopeGuard();

    private static final String EXACT_TICKET_BODY =
            "{\"requesterUserId\":\"018f6f2d-9b57-7c31-a69f-4db0d84db105\""
                    + ",\"schemaVersion\":\"1\",\"serviceRiskLevel\":\"HIGH\""
                    + ",\"ticketId\":\"018f6f2d-9b57-7c31-a69f-4db0d84db102\""
                    + ",\"versionNo\":3"
                    + ",\"workflowDefinitionId\":\"018f6f2d-9b57-7c31-a69f-4db0d84db103\""
                    + ",\"workflowVersionId\":\"018f6f2d-9b57-7c31-a69f-4db0d84db104\"}";

    @Test
    void acceptsExactTicketProducedWireFixture() {
        WorkflowStartV1WireFixture.WireMessage fixture = WorkflowStartV1WireFixture.load();

        assertThat(fixture.body()).containsExactly(EXACT_TICKET_BODY.getBytes(StandardCharsets.UTF_8));
        assertThat(fixture.properties()).hasSize(13)
                .doesNotContainKey("fd_causation_id")
                .containsEntry("fd_body_sha256",
                        "022096dd922b1914cf3eb4242ccb31ddfe519d28e61387de1e274d2e399c1dfb")
                .containsEntry("fd_business_intent_key",
                        "bc5fde73c2ba4fe03c74a1ed19c984504d39e979c08eb8667599920f4b731c1a")
                .containsEntry("fd_correlation_id",
                        "018f6f2d-9b57-7c31-a69f-4db0d84db107");

        AcceptedWorkflowStart accepted = GUARD.validate(fixture.transportMessage());

        assertThat(accepted.tenantId())
                .isEqualTo(UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db101"));
        assertThat(accepted.messageId().toString()).isEqualTo(fixture.keys());
        assertThat(accepted.ticketId())
                .isEqualTo(UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db102"));
        assertThat(accepted.versionNo()).isEqualTo(3);
        assertThat(accepted.correlationId())
                .isEqualTo(UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db107"));
        assertThat(accepted.occurredAt()).isEqualTo(Instant.parse("2026-08-22T01:02:03.123456Z"));
    }

    @Test
    void rejectsUnknownReservedEnvelopeProperty() {
        WorkflowStartV1WireFixture.WireMessage changed = WorkflowStartV1WireFixture.load()
                .withProperty("fd_contract_verison", "1");

        assertThatThrownBy(() -> GUARD.validate(changed.transportMessage()))
                .isInstanceOf(WorkflowStartContractViolation.class)
                .hasMessage("UNKNOWN_ENVELOPE_PROPERTY:fd_contract_verison");
    }

    @Test
    void ignoresForwardCompatibleFdxProperty() {
        WorkflowStartV1WireFixture.WireMessage extended = WorkflowStartV1WireFixture.load()
                .withProperty("fdx_trace_hint", "test-only-extension");

        assertThat(GUARD.validate(extended.transportMessage()).ticketId())
                .isEqualTo(UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db102"));
    }

    @Test
    void acceptsAndRetainsNonZeroCausationId() {
        WorkflowStartV1WireFixture.WireMessage extended = WorkflowStartV1WireFixture.load()
                .withProperty("fd_causation_id", "018f6f2d-9b57-7c31-a69f-4db0d84db109");

        assertThat(GUARD.validate(extended.transportMessage()).causationId())
                .isEqualTo(UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db109"));
    }

    @Test
    void rejectsZeroCausationId() {
        WorkflowStartV1WireFixture.WireMessage changed = WorkflowStartV1WireFixture.load()
                .withProperty("fd_causation_id", "00000000-0000-0000-0000-000000000000");

        assertThatThrownBy(() -> GUARD.validate(changed.transportMessage()))
                .isInstanceOf(WorkflowStartContractViolation.class)
                .hasMessage("CAUSATION_ID_ZERO_FORBIDDEN");
    }

    @Test
    void rejectsMissingMessageKeyAsDeterministicContractViolation() {
        WorkflowStartV1WireFixture.WireMessage changed = WorkflowStartV1WireFixture.load()
                .withKeys(null);

        assertThatThrownBy(() -> GUARD.validate(changed.transportMessage()))
                .isInstanceOf(WorkflowStartContractViolation.class)
                .hasMessage("MESSAGE_KEY_MISMATCH");
    }

    @Test
    void acceptsUnknownOptionalTopLevelPayloadField() {
        byte[] extendedBody = EXACT_TICKET_BODY.replace(
                        "}", ",\"consumerFirstOptionalHint\":\"ignored-by-v1\"}")
                .getBytes(StandardCharsets.UTF_8);
        WorkflowStartV1WireFixture.WireMessage extended = WorkflowStartV1WireFixture.load()
                .withBodyAndMatchingHash(extendedBody);

        assertThat(GUARD.validate(extended.transportMessage()).ticketId())
                .isEqualTo(UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db102"));
    }

    @Test
    void rejectsUtf16JsonEvenWhenJacksonCouldAutoDetectItAndHashMatches() {
        byte[] utf16Body = EXACT_TICKET_BODY.getBytes(StandardCharsets.UTF_16BE);
        WorkflowStartV1WireFixture.WireMessage changed = WorkflowStartV1WireFixture.load()
                .withBodyAndMatchingHash(utf16Body);

        assertThatThrownBy(() -> GUARD.validate(changed.transportMessage()))
                .isInstanceOf(WorkflowStartContractViolation.class)
                .hasMessage("PAYLOAD_INVALID_JSON");
    }

    @Test
    void rejectsMissingRequiredPayloadField() {
        byte[] missingRequester = EXACT_TICKET_BODY.replace(
                        "\"requesterUserId\":\"018f6f2d-9b57-7c31-a69f-4db0d84db105\",", "")
                .getBytes(StandardCharsets.UTF_8);
        WorkflowStartV1WireFixture.WireMessage changed = WorkflowStartV1WireFixture.load()
                .withBodyAndMatchingHash(missingRequester);

        assertThatThrownBy(() -> GUARD.validate(changed.transportMessage()))
                .isInstanceOf(WorkflowStartContractViolation.class)
                .hasMessage("PAYLOAD_FIELD_MISSING:requesterUserId");
    }

    @Test
    void rejectsPayloadTypeDriftEvenWhenBodyHashMatchesChangedBytes() {
        byte[] driftedBody = EXACT_TICKET_BODY
                .replace("\"versionNo\":3", "\"versionNo\":\"3\"")
                .getBytes(StandardCharsets.UTF_8);
        WorkflowStartV1WireFixture.WireMessage changed = WorkflowStartV1WireFixture.load()
                .withBodyAndMatchingHash(driftedBody);

        assertThatThrownBy(() -> GUARD.validate(changed.transportMessage()))
                .isInstanceOf(WorkflowStartContractViolation.class)
                .hasMessage("PAYLOAD_TYPE_INVALID:versionNo");
    }

    @Test
    void rejectsVersionNumberOutsidePositiveIntRange() {
        byte[] overflowedBody = EXACT_TICKET_BODY
                .replace("\"versionNo\":3", "\"versionNo\":2147483648")
                .getBytes(StandardCharsets.UTF_8);
        WorkflowStartV1WireFixture.WireMessage changed = WorkflowStartV1WireFixture.load()
                .withBodyAndMatchingHash(overflowedBody);

        assertThatThrownBy(() -> GUARD.validate(changed.transportMessage()))
                .isInstanceOf(WorkflowStartContractViolation.class)
                .hasMessage("PAYLOAD_TYPE_INVALID:versionNo");
    }

    @Test
    void rejectsHashMismatchBeforePayloadAcceptance() {
        WorkflowStartV1WireFixture.WireMessage changed = WorkflowStartV1WireFixture.load()
                .withProperty("fd_body_sha256", "0".repeat(64));

        assertThatThrownBy(() -> GUARD.validate(changed.transportMessage()))
                .isInstanceOf(WorkflowStartContractViolation.class)
                .hasMessage("BODY_HASH_MISMATCH");
    }

    @Test
    void rejectsWrongConsumerDirectionBeforeTenantAcceptance() {
        WorkflowStartV1WireFixture.WireMessage changed = WorkflowStartV1WireFixture.load()
                .withConsumerGroup("flowdesk_workflow_start_v1_wrong_cg");

        assertThatThrownBy(() -> GUARD.validate(changed.transportMessage()))
                .isInstanceOf(WorkflowStartContractViolation.class)
                .hasMessage("CONSUMER_DIRECTION_MISMATCH");
    }

    @Test
    void rejectsTagBecauseTheFrozenTopicHasNoTagContract() {
        WorkflowStartV1WireFixture.WireMessage changed = WorkflowStartV1WireFixture.load()
                .withTags("unexpected");

        assertThatThrownBy(() -> GUARD.validate(changed.transportMessage()))
                .isInstanceOf(WorkflowStartContractViolation.class)
                .hasMessage("TAG_FORBIDDEN");
    }

    @Test
    void rejectsMissingRequiredEnvelopeProperty() {
        WorkflowStartV1WireFixture.WireMessage changed = WorkflowStartV1WireFixture.load()
                .withoutProperty("fd_contract_version");

        assertThatThrownBy(() -> GUARD.validate(changed.transportMessage()))
                .isInstanceOf(WorkflowStartContractViolation.class)
                .hasMessage("MISSING_ENVELOPE_PROPERTY:fd_contract_version");
    }

    @Test
    void rejectsDuplicateJsonKeys() {
        byte[] duplicateField = EXACT_TICKET_BODY.replace(
                        "}", ",\"schemaVersion\":\"1\"}")
                .getBytes(StandardCharsets.UTF_8);
        WorkflowStartV1WireFixture.WireMessage changed = WorkflowStartV1WireFixture.load()
                .withBodyAndMatchingHash(duplicateField);

        assertThatThrownBy(() -> GUARD.validate(changed.transportMessage()))
                .isInstanceOf(WorkflowStartContractViolation.class)
                .hasMessage("PAYLOAD_INVALID_JSON");
    }
}
