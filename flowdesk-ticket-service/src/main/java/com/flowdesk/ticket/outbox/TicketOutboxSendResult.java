package com.flowdesk.ticket.outbox;

import java.util.Objects;
import java.util.regex.Pattern;

/** 传输结果；只有 {@link Status#SEND_OK} 表示 Broker 已明确确认。 */
public record TicketOutboxSendResult(Status status, String errorCode, String safeErrorSummary) {

    private static final Pattern STABLE_ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,95}");

    /**
     * 构造工作流启动发布记录，并执行当前值对象所要求的输入校验。
     */
    public TicketOutboxSendResult {
        Objects.requireNonNull(status, "status");
        if (status == Status.SEND_OK) {
            if (errorCode != null || safeErrorSummary != null) {
                throw new IllegalArgumentException("SEND_OK must not carry an error");
            }
        }
        else if (errorCode == null || !STABLE_ERROR_CODE.matcher(errorCode).matches()) {
            throw new IllegalArgumentException("A stable error code is required when send is not confirmed");
        }
    }

    /**
     * 创建明确发送成功结果，只有 Broker 成功确认才视为发布完成。
     */
    public static TicketOutboxSendResult sendOk() {
        return new TicketOutboxSendResult(Status.SEND_OK, null, null);
    }

    /**
     * 创建尚未确认成功的传输结果，保留错误码供退避或恢复决策。
     */
    public static TicketOutboxSendResult notConfirmed(String errorCode, String safeErrorSummary) {
        return new TicketOutboxSendResult(Status.NOT_CONFIRMED, errorCode, safeErrorSummary);
    }

    /**
     * 允许的状态枚举，限制请求或传输结果只能使用当前契约定义的状态。
     */
    public enum Status {
        SEND_OK,
        NOT_CONFIRMED
    }
}
