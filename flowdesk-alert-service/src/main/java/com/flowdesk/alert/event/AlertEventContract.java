package com.flowdesk.alert.event;

import java.time.Duration;
import java.util.List;

/** Executable constants for the three M7 Incident collaboration contracts. */
public final class AlertEventContract {
    public static final String ENVELOPE_VERSION = "1";
    public static final int VERSION = 1;
    public static final String SCHEMA_VERSION = "1";
    public static final String AGGREGATE_TYPE = "INCIDENT";
    public static final int MAX_BODY_BYTES = 64 * 1024;
    public static final int MAX_ATTEMPTS = 8;
    public static final int MAX_CLAIMS = 50;
    public static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    public static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);
    public static final int SDK_RETRIES = 0;
    public static final List<Duration> FAILURE_BACKOFFS = List.of(
            Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30),
            Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofMinutes(30),
            Duration.ofHours(2));
    public static final int MAX_RECONSUME_TIMES = 5;
    public static final int DELIVERY_ATTEMPTS = 6;
    public static final int DLQ_ACCEPTOR_MAX_RECONSUME_TIMES = 5;
    public static final int DLQ_ACCEPTOR_RETRY_DELAY_LEVEL = 1;

    private AlertEventContract() { }

    public record Binding(String eventType, String contractRef, String topic,
                          String producer, String producerGroup, String consumerGroup,
                          String dlqAcceptorGroup, String intentNamespace) {
        public String dlqTopic() { return "%DLQ%" + consumerGroup; }
        public String acceptorRetryTopic() { return "%RETRY%" + dlqAcceptorGroup; }
    }

    public static final Binding TICKET_CREATE_REQUESTED = new Binding(
            "flowdesk.incident.ticket-create-requested",
            "flowdesk.incident.ticket-create-requested.v1",
            "flowdesk_incident_ticket_create_requested_v1",
            "flowdesk-alert-service",
            "flowdesk_incident_ticket_create_requested_v1_alert_pg",
            "flowdesk_incident_ticket_create_requested_v1_ticket_cg",
            "flowdesk_incident_ticket_create_requested_v1_ticket_dlq_acceptor_cg",
            "FD-INCIDENT-TICKET-CREATE-REQUESTED-V1");

    public static final Binding TICKET_ESTABLISHED = new Binding(
            "flowdesk.incident.ticket-established",
            "flowdesk.incident.ticket-established.v1",
            "flowdesk_incident_ticket_established_v1",
            "flowdesk-ticket-service",
            "flowdesk_incident_ticket_established_v1_ticket_pg",
            "flowdesk_incident_ticket_established_v1_alert_cg",
            "flowdesk_incident_ticket_established_v1_alert_dlq_acceptor_cg",
            "FD-INCIDENT-TICKET-ESTABLISHED-V1");

    public static final Binding INCIDENT_RECOVERED = new Binding(
            "flowdesk.incident.recovered",
            "flowdesk.incident.recovered.v1",
            "flowdesk_incident_recovered_v1",
            "flowdesk-alert-service",
            "flowdesk_incident_recovered_v1_alert_pg",
            "flowdesk_incident_recovered_v1_ticket_cg",
            "flowdesk_incident_recovered_v1_ticket_dlq_acceptor_cg",
            "FD-INCIDENT-RECOVERED-V1");
}
