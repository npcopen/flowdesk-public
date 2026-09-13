package com.flowdesk.alert.webhook;

import java.time.Instant;
import java.util.UUID;

public record VerifiedWebhook(UUID tenantId, UUID integrationId, String integrationCode,
                              String integrationName, String timestampText, Instant requestTimestamp,
                              String nonce, UUID correlationId, byte[] rawBody, byte[] rawBodySha256) {
    public VerifiedWebhook {
        rawBody = rawBody.clone(); rawBodySha256 = rawBodySha256.clone();
    }
    @Override public byte[] rawBody() { return rawBody.clone(); }
    @Override public byte[] rawBodySha256() { return rawBodySha256.clone(); }
}
