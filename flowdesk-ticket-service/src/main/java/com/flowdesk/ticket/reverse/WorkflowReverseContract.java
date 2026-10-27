package com.flowdesk.ticket.reverse;

/** Ticket 持有的两类固定 M6 Workflow 到 Ticket 绑定，统一实例确认与审批结果契约。 */
public final class WorkflowReverseContract {
    public static final String PRODUCER = "flowdesk-workflow-service";
    public static final String AGGREGATE_TYPE = "WORKFLOW_INSTANCE";
    public static final String ENVELOPE_VERSION = "1";
    public static final int VERSION = 1;
    public static final int MAX_BODY_BYTES = 64 * 1024;
    public static final int MAX_RECONSUME_TIMES = 5;
    public static final int DELIVERY_ATTEMPTS = 1 + MAX_RECONSUME_TIMES;
    // 消息进入主死信队列时 reconsumeTimes 为 6；接收器上限设为 11，才能获得自身的首次加五次重试。
    public static final int DLQ_ACCEPTOR_MAX_RECONSUME_TIMES =
            DELIVERY_ATTEMPTS + MAX_RECONSUME_TIMES;
    public static final int DLQ_ACCEPTOR_RETRY_DELAY_LEVEL = 1;

    /**
     * 私有构造方法，阻止工具类实例化，调用方应使用静态入口。
     */
    private WorkflowReverseContract() { }

    /**
     * 消息绑定支持的业务种类，决定载荷校验及处理分支。
     */
    public enum Kind { INSTANCE_ESTABLISHED, APPROVAL_FINALIZED }

    /**
     * 冻结消息路由与消费绑定，集中声明主题、消费组、类型和版本。
     */
    public record Binding(Kind kind, String eventType, String contractRef, String topic,
                          String consumerGroup, String dlqAcceptorGroup,
                          String intentNamespace) {
        /**
         * 返回冻结死信主题，供主消费重试耗尽后的恢复接收使用。
         */
        public String dlqTopic() { return "%DLQ%" + consumerGroup; }
        /**
         * 返回死信接收器专用重试主题，隔离主消费与接收器重试节奏。
         */
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
