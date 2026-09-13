package com.flowdesk.workflow.start;

/** A temporary IAM dependency failure that must be returned to the Broker. */
public final class WorkflowIamUnavailableException extends RuntimeException {

    public WorkflowIamUnavailableException() {
        super("IAM_ELIGIBILITY_UNAVAILABLE");
    }

    public WorkflowIamUnavailableException(Throwable cause) {
        super("IAM_ELIGIBILITY_UNAVAILABLE", cause);
    }
}
