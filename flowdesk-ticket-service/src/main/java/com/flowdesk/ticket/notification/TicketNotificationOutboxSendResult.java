package com.flowdesk.ticket.notification;

import java.util.Objects;
import java.util.regex.Pattern;

/** 工单通知发送结果；仅 SEND_OK 表示 Broker 确认，其余结果保留稳定错误码以便重试。 */
public record TicketNotificationOutboxSendResult(Status status, String errorCode,
                                                 String safeErrorSummary) {
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,95}");
    /**
     * 构造工单通知记录，并执行当前值对象所要求的输入校验。
     */
    public TicketNotificationOutboxSendResult {
        Objects.requireNonNull(status, "status");
        if (status == Status.SEND_OK) {
            if (errorCode != null || safeErrorSummary != null) throw new IllegalArgumentException("SEND_OK must not carry an error");
        } else if (errorCode == null || !CODE.matcher(errorCode).matches()) {
            throw new IllegalArgumentException("A stable error code is required");
        }
    }
    /**
     * 创建明确发送成功结果，只有 Broker 成功确认才视为发布完成。
     */
    public static TicketNotificationOutboxSendResult sendOk() { return new TicketNotificationOutboxSendResult(Status.SEND_OK, null, null); }
    /**
     * 创建尚未确认成功的传输结果，保留错误码供退避或恢复决策。
     */
    public static TicketNotificationOutboxSendResult notConfirmed(String code, String summary) { return new TicketNotificationOutboxSendResult(Status.NOT_CONFIRMED, code, summary); }
    /**
     * 允许的状态枚举，限制请求或传输结果只能使用当前契约定义的状态。
     */
    public enum Status { SEND_OK, NOT_CONFIRMED }
}
