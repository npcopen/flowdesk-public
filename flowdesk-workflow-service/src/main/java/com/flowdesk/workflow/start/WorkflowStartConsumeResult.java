package com.flowdesk.workflow.start;

import java.util.Objects;
import java.util.UUID;

/**
 * 启动消费入口返回给 Broker 的唯一裁决，明确区分确认与重试。
 */
public record WorkflowStartConsumeResult(
        BrokerDisposition disposition,
        String code,
        UUID workflowInstanceId) {

    /**
     * 构造单次消费处置并强制要求处置分类及错误码非空，供监听器直接映射代理行为。
     */
    public WorkflowStartConsumeResult {
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(code, "code");
    }

    /**
     * 代理处置分类，区分可以确认的业务结果与需要再次投递的暂时失败。
     */
    public enum BrokerDisposition {
        ACK,
        RETRY
    }

    /**
     * 构造可向 Broker 确认的结果，错误码说明接纳或拒绝的业务原因。
     */
    public static WorkflowStartConsumeResult ack(String code, UUID workflowInstanceId) {
        return new WorkflowStartConsumeResult(BrokerDisposition.ACK, code, workflowInstanceId);
    }

    /**
     * 构造需要代理重投的结果，将暂时性故障交由外层有界重试处理。
     */
    public static WorkflowStartConsumeResult retry(String code) {
        return new WorkflowStartConsumeResult(BrokerDisposition.RETRY, code, null);
    }
}
