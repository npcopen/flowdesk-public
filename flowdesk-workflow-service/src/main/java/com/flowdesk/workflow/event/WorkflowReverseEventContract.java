package com.flowdesk.workflow.event;

import java.time.Duration;
import java.util.List;

/**
 * 集中声明 Workflow 向 Ticket 回传的两个固定事件契约及其消息绑定。
 */
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

    /**
     * 禁止直接实例化工具容器，相关静态规则与转换由调用方按业务需要复用。
     */
    private WorkflowReverseEventContract() {
    }

    /**
     * 工作流内部协作类型，组合当前处理所需的结构与行为，供所在用例的依赖链调用。
     */
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

        /**
         * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
         */
        private InstanceEstablished() {
        }
    }

    /**
     * 工作流内部协作类型，组合当前处理所需的结构与行为，供所在用例的依赖链调用。
     */
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

        /**
         * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
         */
        private ApprovalFinalized() {
        }
    }
}
