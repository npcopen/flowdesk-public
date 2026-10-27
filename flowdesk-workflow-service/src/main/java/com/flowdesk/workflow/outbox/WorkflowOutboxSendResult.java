package com.flowdesk.workflow.outbox;

/**
 * 反向事件的 Broker 确认结果，供发布器区分成功、待重试和最终失败。
 */
public record WorkflowOutboxSendResult(Status status, String errorCode, String safeSummary) {
    /**
     * 工作流内部协作类型，组合当前处理所需的结构与行为，供所在用例的依赖链调用。
     */
    public enum Status { SEND_OK, NOT_CONFIRMED }

    /**
     * 构造代理明确确认发送成功的结果，供发布器结算为已发布。
     */
    public static WorkflowOutboxSendResult sendOk() {
        return new WorkflowOutboxSendResult(Status.SEND_OK, null, null);
    }

    /**
     * 构造发送尚未明确确认的结果，保留重试或恢复处理路径。
     */
    public static WorkflowOutboxSendResult notConfirmed(String code, String summary) {
        return new WorkflowOutboxSendResult(Status.NOT_CONFIRMED, code, summary);
    }
}
