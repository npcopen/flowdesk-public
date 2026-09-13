package com.flowdesk.ticket.reverse;

/** Ticket-owned copy of the two frozen M6 Workflow -> Ticket bindings. */
public final class WorkflowReverseContract {
    public static final String PRODUCER = "flowdesk-workflow-service";
    public static final String AGGREGATE_TYPE = "WORKFLOW_INSTANCE";
    public static final String ENVELOPE_VERSION = "1";
    public static final int VERSION = 1;
    public static final int MAX_BODY_BYTES = 64 * 1024;
    public static final int MAX_RECONSUME_TIMES = 5;
    public static final int DELIVERY_ATTEMPTS = 1 + MAX_RECONSUME_TIMES;
    // A message enters the main DLQ with reconsumeTimes=6. The acceptor must
    // therefore use an absolute ceiling of 11 to receive its own 1+5 attempts.
    public static final int DLQ_ACCEPTOR_MAX_RECONSUME_TIMES =
            DELIVERY_ATTEMPTS + MAX_RECONSUME_TIMES;
    public static final int DLQ_ACCEPTOR_RETRY_DELAY_LEVEL = 1;

    private WorkflowReverseContract() { }

    public enum Kind { INSTANCE_ESTABLISHED, APPROVAL_FINALIZED }

    public record Binding(Kind kind, String eventType, String contractRef, String topic,
                          String consumerGroup, String dlqAcceptorGroup,
                          String intentNamespace) {
        public String dlqTopic() { return "%DLQ%" + consumerGroup; }
        public String acceptorRetryTopic() { return "%RETRY%" + dlqAcceptorGroup; }
    }

    public static final Binding INSTANCE_ESTABLISHED = new Binding(
            Kind.INSTANCE_ESTABLISHED,
            "flowdesk.workflow.instance-established",
            "flowdesk.workflow.instance-established.v1",
            "flowdesk_workflow_instance_established_v1",
            "flowdesk_workflow_instance_established_v1_ticket_cg",
            "flowdesk_workflow_instance_established_v1_ticket_dlq_acceptor_cg",
            "FD-WORKFLOW-INSTANCE-ESTABLISHED-V1");

    public static final Binding APPROVAL_FINALIZED = new Binding(
            Kind.APPROVAL_FINALIZED,
            "flowdesk.workflow.approval-finalized",
            "flowdesk.workflow.approval-finalized.v1",
            "flowdesk_workflow_approval_finalized_v1",
            "flowdesk_workflow_approval_finalized_v1_ticket_cg",
            "flowdesk_workflow_approval_finalized_v1_ticket_dlq_acceptor_cg",
            "FD-WORKFLOW-APPROVAL-FINALIZED-V1");
}
