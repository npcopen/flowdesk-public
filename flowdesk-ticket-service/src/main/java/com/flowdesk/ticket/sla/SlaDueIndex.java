package com.flowdesk.ticket.sla;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Candidate-only SLA due index.  MySQL remains the authority for every state,
 * deadline, trigger and recipient decision.
 */
public interface SlaDueIndex {
    void upsert(UUID tenantId, UUID slaClockId, String stage, Instant dueAt);

    void remove(UUID tenantId, UUID slaClockId, String stage);

    List<Candidate> due(UUID tenantId, Instant now, int limit);

    void clear(UUID tenantId);

    /** Returns false when this adapter cannot currently be used. */
    boolean available();

    record Candidate(UUID tenantId, UUID slaClockId, String stage, double score) {
    }
}
