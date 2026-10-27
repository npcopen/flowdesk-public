package com.flowdesk.workflow.start;

/**
 * 表示 IAM 暂时不可用；消息消费入口据此交还 Broker 重试，避免误判为永久拒绝。
 */
public final class WorkflowIamUnavailableException extends RuntimeException {

    /**
     * 创建异常或专用实现并保留传入原因，供调用方分类处理。
     */
    public WorkflowIamUnavailableException() {
        super("IAM_ELIGIBILITY_UNAVAILABLE");
    }

    /**
     * 创建异常或专用实现并保留传入原因，供调用方分类处理。
     */
    public WorkflowIamUnavailableException(Throwable cause) {
        super("IAM_ELIGIBILITY_UNAVAILABLE", cause);
    }
}
