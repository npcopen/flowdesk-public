package com.flowdesk.workflow.start;

/** Deterministic pre-tenant poison-message rejection. */
public final class WorkflowStartContractViolation extends IllegalArgumentException {

    private final String code;

    WorkflowStartContractViolation(String code) {
        super(code);
        this.code = code;
    }

    WorkflowStartContractViolation(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
