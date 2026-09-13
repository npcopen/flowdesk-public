package com.flowdesk.ticket.reverse;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowReverseV1ConsumerContractFixtureTest {
    private static final String ESTABLISHED =
            "contracts/flowdesk-workflow-instance-established-v1-wire.properties";
    private static final String FINALIZED =
            "contracts/flowdesk-workflow-approval-finalized-v1-wire.properties";

    @Test
    void independentTicketGuardAcceptsTheExactInstanceEstablishedFixture() throws Exception {
        AcceptedWorkflowReverse accepted = validate(ESTABLISHED,
                WorkflowReverseContract.INSTANCE_ESTABLISHED);
        assertThat(accepted.binding().kind())
                .isEqualTo(WorkflowReverseContract.Kind.INSTANCE_ESTABLISHED);
        assertThat(accepted.instanceStatus()).isEqualTo("RUNNING");
        assertThat(accepted.outcome()).isNull();
    }

    @Test
    void independentTicketGuardAcceptsTheExactApprovalFinalizedFixture() throws Exception {
        AcceptedWorkflowReverse accepted = validate(FINALIZED,
                WorkflowReverseContract.APPROVAL_FINALIZED);
        assertThat(accepted.binding().kind())
                .isEqualTo(WorkflowReverseContract.Kind.APPROVAL_FINALIZED);
        assertThat(accepted.instanceStatus()).isNull();
        assertThat(accepted.outcome()).isEqualTo("PASSED");
    }

    @Test
    void bothTicketBindingsFreezeTheirOwnMainAndAcceptorRetryBudgets() {
        assertThat(WorkflowReverseContract.MAX_RECONSUME_TIMES).isEqualTo(5);
        assertThat(WorkflowReverseContract.DELIVERY_ATTEMPTS).isEqualTo(6);
        assertThat(WorkflowReverseContract.DLQ_ACCEPTOR_MAX_RECONSUME_TIMES).isEqualTo(11);
        assertThat(WorkflowReverseContract.INSTANCE_ESTABLISHED.dlqTopic())
                .isEqualTo("%DLQ%flowdesk_workflow_instance_established_v1_ticket_cg");
        assertThat(WorkflowReverseContract.APPROVAL_FINALIZED.dlqTopic())
                .isEqualTo("%DLQ%flowdesk_workflow_approval_finalized_v1_ticket_cg");
    }

    private static AcceptedWorkflowReverse validate(String path,
                                                      WorkflowReverseContract.Binding binding)
            throws Exception {
        Properties fixture = load(path);
        byte[] body = Base64.getDecoder().decode(required(fixture, "body.base64"));
        Map<String, String> properties = new LinkedHashMap<>();
        fixture.stringPropertyNames().stream().filter(name -> name.startsWith("fd_"))
                .forEach(name -> properties.put(name, required(fixture, name)));
        AcceptedWorkflowReverse accepted = new WorkflowReverseEnvelopeGuard().validateMain(
                new WorkflowReverseTransportMessage(required(fixture, "topic"),
                        required(fixture, "consumer.group"), required(fixture, "keys"),
                        null, body, properties));
        assertThat(accepted.bodyBytes()).containsExactly(body);
        assertThat(accepted.messageId()).isEqualTo(UUID.fromString(required(fixture, "keys")));
        assertThat(binding.eventType()).isEqualTo(required(fixture, "fd_event_type"));
        assertThat(binding.contractRef()).isEqualTo(required(fixture, "fd_contract_ref"));
        assertThat(binding.topic()).isEqualTo(required(fixture, "topic"));
        assertThat(binding.consumerGroup()).isEqualTo(required(fixture, "consumer.group"));
        assertThat(binding.dlqAcceptorGroup()).isEqualTo(required(fixture, "dlq.acceptor.group"));
        return accepted;
    }

    private static Properties load(String path) throws Exception {
        Properties value = new Properties();
        try (InputStream input = WorkflowReverseV1ConsumerContractFixtureTest.class
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
