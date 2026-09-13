package com.flowdesk.ticket.reverse;

public record WorkflowReverseConsumeResult(Disposition disposition, String code) {
    public enum Disposition { ACK, RETRY }
    public static WorkflowReverseConsumeResult ack(String code) {
        return new WorkflowReverseConsumeResult(Disposition.ACK, code);
    }
    public static WorkflowReverseConsumeResult retry(String code) {
        return new WorkflowReverseConsumeResult(Disposition.RETRY, code);
    }
}
