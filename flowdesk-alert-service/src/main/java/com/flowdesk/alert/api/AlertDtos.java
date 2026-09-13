package com.flowdesk.alert.api;

import com.flowdesk.common.web.AsyncProgress;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AlertDtos {
    private AlertDtos() { }

    public record WebhookReceipt(UUID alertEventId, String eventId, String disposition,
                                 UUID incidentId, String incidentStatus, String rawBodySha256,
                                 Instant receivedAt, List<AsyncProgress> async) { }
    public record IntegrationRef(UUID integrationId, String integrationCode,
                                 String integrationName) { }
    public record AlertEventView(UUID alertEventId, IntegrationRef integrationRef,
                                 String externalEventId, String eventType, String fingerprint,
                                 String cycleRef, Instant occurredAt, Instant receivedAt,
                                 String disposition, UUID incidentId, String rawBodySha256,
                                 String summary, UUID correlationId) { }
    public record IncidentView(UUID incidentId, String fingerprint, int generationNo,
                               String status, int eventCount, Instant openedAt,
                               Instant lastEventAt, Instant recoveredAt, String summary,
                               long lockVersion, Instant createdAt, Instant updatedAt) { }
    public record IncidentTicketLinkView(UUID incidentId, String status, UUID ticketId,
                                         Instant requestedAt, Instant establishedAt,
                                         Instant recoveryRequiredAt, String recoveryReasonCode,
                                         int retryCount, Instant lastAttemptAt, long lockVersion,
                                         Instant createdAt, Instant updatedAt) { }
}
