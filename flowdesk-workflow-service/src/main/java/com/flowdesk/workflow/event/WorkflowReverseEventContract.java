package com.flowdesk.workflow.event;

import java.time.Duration;
import java.util.List;

/** Frozen M6 Workflow->Ticket event bindings. */
public final class WorkflowReverseEventContract {

    public static final String PRODUCER_SERVICE = "flowdesk-workflow-service";
    public static final String AGGREGATE_TYPE = "WORKFLOW_INSTANCE";
    public static final String ENVELOPE_VERSION = "1";
    public static final String PAYLOAD_SCHEMA_VERSION = "1";
    public static final int CONTRACT_VERSION = 1;
    public static final int MAX_BODY_BYTES = 64 * 1024;
    public static final int OUTBOX_MAX_ATTEMPTS = 8;
    public static final int OUTBOX_MAX_CLAIMS = 50;
    public static final Duration OUTBOX_CLAIM_LEASE = Duration.ofSeconds(30);
    public static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration EMPTY_POLL_INTERVAL = Duration.ofMillis(500);
    public static final int SDK_SEND_RETRIES = 0;
    public static final List<Duration> FAILURE_BACKOFFS = List.of(
            Duration.ofSeconds(1),
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofMinutes(10),
            Duration.ofMinutes(30),
            Duration.ofHours(2));
    public static final int MAX_RECONSUME_TIMES = 5;
    public static final int MAIN_DELIVERY_ATTEMPTS = 1 + MAX_RECONSUME_TIMES;

    private WorkflowReverseEventContract() {
    }

    public static final class InstanceEstablished {
        public static final String EVENT_TYPE = "flowdesk.workflow.instance-established";
        public static final String CONTRACT_REF = "flowdesk.workflow.instance-established.v1";
        public static final String TOPIC = "flowdesk_workflow_instance_established_v1";
        public static final String PRODUCER_GROUP =
                "flowdesk_workflow_instance_established_v1_workflow_pg";
        public static final String CONSUMER_GROUP =
                "flowdesk_workflow_instance_established_v1_ticket_cg";
        public static final String DLQ_ACCEPTOR_GROUP =
                "flowdesk_workflow_instance_established_v1_ticket_dlq_acceptor_cg";
        public static final String BUSINESS_INTENT_NAMESPACE =
                "FD-WORKFLOW-INSTANCE-ESTABLISHED-V1";

        private InstanceEstablished() {
        }
    }

    public static final class ApprovalFinalized {
        public static final String EVENT_TYPE = "flowdesk.workflow.approval-finalized";
        public static final String CONTRACT_REF = "flowdesk.workflow.approval-finalized.v1";
        public static final String TOPIC = "flowdesk_workflow_approval_finalized_v1";
        public static final String PRODUCER_GROUP =
                "flowdesk_workflow_approval_finalized_v1_workflow_pg";
        public static final String CONSUMER_GROUP =
                "flowdesk_workflow_approval_finalized_v1_ticket_cg";
        public static final String DLQ_ACCEPTOR_GROUP =
                "flowdesk_workflow_approval_finalized_v1_ticket_dlq_acceptor_cg";
        public static final String BUSINESS_INTENT_NAMESPACE =
                "FD-WORKFLOW-APPROVAL-FINALIZED-V1";

        private ApprovalFinalized() {
        }
    }
}
