package com.flowdesk.ticket.incident;

/** Incident 消息契约校验失败，使用稳定错误码区分非法消息原因。 */
public final class IncidentContractViolation extends RuntimeException{
    private final String code;
    /**
     * 构造告警建单异常，保存稳定错误信息及可用的原始原因，供上层分类处理。
     */
    public IncidentContractViolation(String code){this.code=code;}
    /**
     * 构造告警建单异常，保存稳定错误信息及可用的原始原因，供上层分类处理。
     */
    public IncidentContractViolation(String code,Throwable cause){super(cause);this.code=code;}
    /**
     * 返回稳定错误码，供消费、恢复及 API 统一分类。
     */
    public String code(){return code;}
}
