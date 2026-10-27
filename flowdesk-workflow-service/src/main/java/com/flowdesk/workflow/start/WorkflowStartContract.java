package com.flowdesk.workflow.start;

/**
 * 工作流启动契约 flowdesk.workflow.start.v1 的固定常量及消息组绑定。
 */
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
     * 主组死信保留原 reconsume 计数；在继承值上增加接收器自己的五次重试预算，
     * 避免接收器第一次暂时失败就被转入二级死信队列。
     */
    // RocketMQ 死信记录保留主消费组的 reconsumeTimes=6。
    // 将绝对上限设为 11，使独立接收器仍有首次投递加五次重试的预算。
    public static final int DLQ_ACCEPTOR_MAX_RECONSUME_TIMES =
            MAIN_DELIVERY_ATTEMPTS + MAX_RECONSUME_TIMES;
    public static final int DLQ_ACCEPTOR_RETRY_DELAY_LEVEL = 1;

    /**
     * 禁止直接实例化工具容器，相关静态规则与转换由调用方按业务需要复用。
     */
    private WorkflowStartContract() {
    }
}
