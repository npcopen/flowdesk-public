package com.flowdesk.ticket.incident;

public record IncidentConsumeResult(Disposition disposition,String code){public enum Disposition{ACK,RETRY}
    public static IncidentConsumeResult ack(String code){return new IncidentConsumeResult(Disposition.ACK,code);}
    public static IncidentConsumeResult retry(String code){return new IncidentConsumeResult(Disposition.RETRY,code);}}
