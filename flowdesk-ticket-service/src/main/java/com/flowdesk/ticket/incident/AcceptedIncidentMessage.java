package com.flowdesk.ticket.incident;

import java.time.Instant;
import java.util.UUID;

public record AcceptedIncidentMessage(IncidentMessageContract.Binding binding,UUID tenantId,
        UUID messageId,byte[] businessIntentKey,UUID incidentId,byte[] createTicketIntentKey,
        String fingerprint,Integer generationNo,String cycleRef,String summary,Instant openedAt,
        UUID recoveryEventId,Instant recoveredAt,UUID correlationId,UUID causationId,
        Instant envelopeOccurredAt,byte[] bodyBytes,byte[] bodySha256){
    public AcceptedIncidentMessage{businessIntentKey=businessIntentKey.clone();
        createTicketIntentKey=createTicketIntentKey.clone();bodyBytes=bodyBytes.clone();bodySha256=bodySha256.clone();}
    @Override public byte[] businessIntentKey(){return businessIntentKey.clone();}
    @Override public byte[] createTicketIntentKey(){return createTicketIntentKey.clone();}
    @Override public byte[] bodyBytes(){return bodyBytes.clone();}
    @Override public byte[] bodySha256(){return bodySha256.clone();}
}
