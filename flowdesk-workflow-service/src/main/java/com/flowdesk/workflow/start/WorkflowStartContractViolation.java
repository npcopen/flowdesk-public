package com.flowdesk.workflow.start;

/**
 * 表示在建立租户上下文前即可确定的消息契约错误，供入口拒绝毒消息。
 */
public final class WorkflowStartContractViolation extends IllegalArgumentException {

    private final String code;

    /**
     * 创建异常或专用实现并保留传入原因，供调用方分类处理。
     */
    WorkflowStartContractViolation(String code) {
        super(code);
        this.code = code;
    }

    /**
     * 创建异常或专用实现并保留传入原因，供调用方分类处理。
     */
    WorkflowStartContractViolation(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    /**
     * 取得稳定错误码，供调用方分类处理并保存有限长度错误信息。
     */
    public String code() {
        return code;
    }
}
