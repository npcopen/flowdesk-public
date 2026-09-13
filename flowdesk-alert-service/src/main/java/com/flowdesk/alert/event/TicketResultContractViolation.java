package com.flowdesk.alert.event;
final class TicketResultContractViolation extends RuntimeException{private final String code;TicketResultContractViolation(String c){super(c);code=c;}TicketResultContractViolation(String c,Throwable x){super(c,x);code=c;}String code(){return code;}}
