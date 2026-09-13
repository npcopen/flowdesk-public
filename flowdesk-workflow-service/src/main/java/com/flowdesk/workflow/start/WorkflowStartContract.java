package com.flowdesk.workflow.start;

/** Frozen constants for {@code flowdesk.workflow.start.v1}. */
public final class WorkflowStartContract {

    public static final String TOPIC = "flowdesk_workflow_start_v1";
    public static final String CONSUMER_GROUP = "flowdesk_workflow_start_v1_workflow_cg";
    public static final String DLQ_TOPIC = "%DLQ%" + CONSUMER_GROUP;
    public static final String DLQ_ACCEPTOR_GROUP =
            "flowdesk_workflow_start_v1_workflow_dlq_acceptor_cg";
    public static final String DLQ_ACCEPTOR_RETRY_TOPIC = "%RETRY%" + DLQ_ACCEPTOR_GROUP;
    public static final String EVENT_TYPE = "flowdesk.workflow.start";
    public static final String CONTRACT_REF = "flowdesk.workflow.start.v1";
    public static final int CONTRACT_VERSION = 1;
    public static final String PRODUCER_SERVICE = "flowdesk-ticket-service";
    public static final String AGGREGATE_TYPE = "TICKET";
    public static final String ENVELOPE_VERSION = "1";
    public static final String PAYLOAD_SCHEMA_VERSION = "1";
    public static final String BUSINESS_INTENT_KEY_NAMESPACE = "FD-WORKFLOW-START-V1";
    public static final int MAX_BODY_BYTES = 64 * 1024;
    public static final int MAX_RECONSUME_TIMES = 5;
    public static final int MAIN_DELIVERY_ATTEMPTS = 1 + MAX_RECONSUME_TIMES;
    /**
     * A main-DLQ message retains the main group's reconsume count.  Adding the acceptor's own
     * five-reconsume budget to that inherited count prevents its first transient failure from
     * being routed directly to the secondary DLQ.
     */
    // RocketMQ preserves the main consumer's reconsumeTimes=6 on the DLQ row.
    // An absolute ceiling of 11 gives the independent acceptor its own 1+5 attempts.
    public static final int DLQ_ACCEPTOR_MAX_RECONSUME_TIMES =
            MAIN_DELIVERY_ATTEMPTS + MAX_RECONSUME_TIMES;
    public static final int DLQ_ACCEPTOR_RETRY_DELAY_LEVEL = 1;

    private WorkflowStartContract() {
    }
}
