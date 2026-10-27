package com.flowdesk.ticket.incident;

/** Incident 消费结果：ACK 结束本次投递，RETRY 交回 Broker 重试，并携带稳定原因码。 */
public record IncidentConsumeResult(Disposition disposition,String code){
    /**
     * 消费确认分类，区分无需再试的确认结果与应继续重试的情况。
     */
    public enum Disposition{ACK,RETRY}
    /**
     * 创建确认消费结果，通知传输层当前消息无需继续重试。
     */
    public static IncidentConsumeResult ack(String code){return new IncidentConsumeResult(Disposition.ACK,code);}
    /**
     * 创建或执行受控重试安排，使尚未收敛的处理在后续投递中继续。
     */
    public static IncidentConsumeResult retry(String code){return new IncidentConsumeResult(Disposition.RETRY,code);}}
