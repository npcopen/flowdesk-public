package com.flowdesk.ticket.event;

import java.time.Duration;
import java.util.List;

/**
 * Executable integration-event constants emitted by Ticket.
 */
public final class TicketEventContract {

    private TicketEventContract() {
    }

    public static final class WorkflowStartRequested {
        public static final String EVENT_TYPE = "flowdesk.workflow.start";
        public static final int EVENT_VERSION = 1;
        public static final String CONTRACT_REF = EVENT_TYPE + ".v" + EVENT_VERSION;
        public static final String PAYLOAD_SCHEMA_VERSION = Integer.toString(EVENT_VERSION);
        public static final String AGGREGATE_TYPE = "TICKET";
        public static final String TOPIC = "flowdesk_workflow_start_v1";
        public static final String PRODUCER_SERVICE = "flowdesk-ticket-service";
        public static final String PRODUCER_GROUP = "flowdesk_workflow_start_v1_ticket_pg";
        public static final String ENVELOPE_VERSION = "1";
        public static final String BUSINESS_INTENT_KEY_NAMESPACE = "FD-WORKFLOW-START-V1";
        public static final String PUBLICATION_KEY_NAMESPACE = "FD-PUB-V1";
        public static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);
        public static final int SYNCHRONOUS_SEND_RETRIES = 0;
        public static final int ASYNCHRONOUS_SEND_RETRIES = 0;
        public static final int MAX_CLAIMS_PER_RUN = 50;
        public static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
        public static final Duration EMPTY_POLL_INTERVAL = Duration.ofMillis(500);
        public static final int MAX_AUTOMATIC_SENDS = 8;
        public static final List<Duration> FAILURE_BACKOFFS = List.of(
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2),
                Duration.ofMinutes(10),
                Duration.ofMinutes(30),
                Duration.ofHours(2));

        private WorkflowStartRequested() {
        }
    }
}
