package com.flowdesk.ticket.incident;

public final class IncidentContractViolation extends RuntimeException{
    private final String code;public IncidentContractViolation(String code){this.code=code;}
    public IncidentContractViolation(String code,Throwable cause){super(cause);this.code=code;}
    public String code(){return code;}
}
