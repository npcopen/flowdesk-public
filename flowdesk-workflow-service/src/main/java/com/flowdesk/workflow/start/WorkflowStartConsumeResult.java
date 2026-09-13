package com.flowdesk.workflow.start;

import java.util.Objects;
import java.util.UUID;

/** The only result a later Broker adapter may map to ACK or retry. */
public record WorkflowStartConsumeResult(
        BrokerDisposition disposition,
        String code,
        UUID workflowInstanceId) {

    public WorkflowStartConsumeResult {
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(code, "code");
    }

    public enum BrokerDisposition {
        ACK,
        RETRY
    }

    static WorkflowStartConsumeResult ack(String code, UUID workflowInstanceId) {
        return new WorkflowStartConsumeResult(BrokerDisposition.ACK, code, workflowInstanceId);
    }

    static WorkflowStartConsumeResult retry(String code) {
        return new WorkflowStartConsumeResult(BrokerDisposition.RETRY, code, null);
    }
}
