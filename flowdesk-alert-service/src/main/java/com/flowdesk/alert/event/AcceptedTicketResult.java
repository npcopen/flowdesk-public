package com.flowdesk.alert.event;

import java.time.Instant;
import java.util.UUID;

record AcceptedTicketResult(UUID tenantId,UUID messageId,byte[] businessIntentKey,UUID incidentId,
        byte[] createTicketIntentKey,UUID ticketId,Instant establishedAt,UUID correlationId,
        UUID causationId,Instant envelopeOccurredAt,byte[] body,byte[] bodyHash){
    AcceptedTicketResult{businessIntentKey=businessIntentKey.clone();createTicketIntentKey=createTicketIntentKey.clone();body=body.clone();bodyHash=bodyHash.clone();}
    @Override public byte[] businessIntentKey(){return businessIntentKey.clone();}@Override public byte[] createTicketIntentKey(){return createTicketIntentKey.clone();}
    @Override public byte[] body(){return body.clone();}@Override public byte[] bodyHash(){return bodyHash.clone();}}
