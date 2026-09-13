package com.flowdesk.ticket.reverse;

final class WorkflowReverseContractViolation extends RuntimeException {
    private final String code;
    WorkflowReverseContractViolation(String code) { super(code); this.code = code; }
    WorkflowReverseContractViolation(String code, Throwable cause) {
        super(code, cause); this.code = code;
    }
    String code() { return code; }
}
