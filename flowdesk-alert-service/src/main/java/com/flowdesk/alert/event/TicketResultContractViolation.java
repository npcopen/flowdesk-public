package com.flowdesk.alert.event;

/**
 * 标识路由、信封或正文不符合工单结果契约的确定性错误，保留稳定错误码和可选底层原因。
 *
 * <p>主消费和死信入口捕获此异常后直接 ACK，因为重投不会修复报文，且尚不能信任其租户。
 * 它与数据库暂时失败分开，使后者仍能触发重试，而不是被误判为应丢弃的契约消息。
 */
final class TicketResultContractViolation extends RuntimeException {
    private final String code;

    /**
     * 构造携带稳定错误码的消息契约违规异常，按需保留底层原因，供校验和拒绝路径统一处理。
     */
    TicketResultContractViolation(String c) {
        super(c);
        code = c;
    }

    /**
     * 构造携带稳定错误码的消息契约违规异常，按需保留底层原因，供校验和拒绝路径统一处理。
     */
    TicketResultContractViolation(String c, Throwable x) {
        super(c, x);
        code = c;
    }

    /**
     * 返回消息契约校验失败的稳定错误码，供拒绝或审计逻辑区分违规原因。
     */
    String code() {
        return code;
    }
}
