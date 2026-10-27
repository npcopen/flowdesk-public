package com.flowdesk.ticket.reverse;

/** 流程反向事件的消费裁决，以 ACK 或 RETRY 和稳定错误码反馈给消息适配层。 */
public record WorkflowReverseConsumeResult(Disposition disposition, String code) {
    /**
     * 消费确认分类，区分无需再试的确认结果与应继续重试的情况。
     */
    public enum Disposition { ACK, RETRY }
    /**
     * 创建确认消费结果，通知传输层当前消息无需继续重试。
     */
    public static WorkflowReverseConsumeResult ack(String code) {
        return new WorkflowReverseConsumeResult(Disposition.ACK, code);
    }
    /**
     * 创建或执行受控重试安排，使尚未收敛的处理在后续投递中继续。
     */
    public static WorkflowReverseConsumeResult retry(String code) {
        return new WorkflowReverseConsumeResult(Disposition.RETRY, code);
    }
}
