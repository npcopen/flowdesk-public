package com.flowdesk.ticket.sla;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Explicit degraded adapter used when Redis indexing is disabled. */
public final class NoopSlaDueIndex implements SlaDueIndex {
    @Override public void upsert(UUID tenantId, UUID slaClockId, String stage, Instant dueAt) { }
    @Override public void remove(UUID tenantId, UUID slaClockId, String stage) { }
    @Override public List<Candidate> due(UUID tenantId, Instant now, int limit) { return List.of(); }
    @Override public void clear(UUID tenantId) { }
    @Override public boolean available() { return false; }
}
