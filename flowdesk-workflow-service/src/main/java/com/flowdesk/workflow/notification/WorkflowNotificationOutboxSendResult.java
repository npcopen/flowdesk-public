package com.flowdesk.workflow.notification;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 通知发送结果：仅 SEND_OK 表示 Broker 已确认，其余结果进入重试或失败处理。
 */
public record WorkflowNotificationOutboxSendResult(
        Status status, String errorCode, String safeErrorSummary) {
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,95}");

    /**
     * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
     */
    public WorkflowNotificationOutboxSendResult {
        Objects.requireNonNull(status, "status");
        if (status == Status.SEND_OK) {
            if (errorCode != null || safeErrorSummary != null) {
                throw new IllegalArgumentException("SEND_OK must not carry an error");
            }
        }
        else if (errorCode == null || !CODE.matcher(errorCode).matches()) {
            throw new IllegalArgumentException("A stable error code is required");
        }
    }

    /**
     * 构造代理明确确认发送成功的结果，供发布器结算为已发布。
     */
    public static WorkflowNotificationOutboxSendResult sendOk() {
        return new WorkflowNotificationOutboxSendResult(Status.SEND_OK, null, null);
    }

    /**
     * 构造发送尚未明确确认的结果，保留重试或恢复处理路径。
     */
    public static WorkflowNotificationOutboxSendResult notConfirmed(String code, String summary) {
        return new WorkflowNotificationOutboxSendResult(Status.NOT_CONFIRMED, code, summary);
    }

    /**
     * 工作流内部协作类型，组合当前处理所需的结构与行为，供所在用例的依赖链调用。
     */
    public enum Status { SEND_OK, NOT_CONFIRMED }
}
