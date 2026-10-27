package com.flowdesk.ticket.reverse;

/** 流程反向事件不满足固定契约时的校验异常，携带可安全处理的稳定原因码。 */
final class WorkflowReverseContractViolation extends RuntimeException {
    private final String code;
    /**
     * 构造工作流反向结果异常，保存稳定错误信息及可用的原始原因，供上层分类处理。
     */
    WorkflowReverseContractViolation(String code) { super(code); this.code = code; }
    /**
     * 构造工作流反向结果异常，保存稳定错误信息及可用的原始原因，供上层分类处理。
     */
    WorkflowReverseContractViolation(String code, Throwable cause) {
        super(code, cause); this.code = code;
    }
    /**
     * 返回稳定错误码，供消费、恢复及 API 统一分类。
     */
    String code() { return code; }
}
