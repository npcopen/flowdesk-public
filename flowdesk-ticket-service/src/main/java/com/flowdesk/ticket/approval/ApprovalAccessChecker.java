package com.flowdesk.ticket.approval;

import java.util.UUID;

/** Verifies an actual assigned approval before exposing its application summary. */
public interface ApprovalAccessChecker {
    boolean canRead(UUID tenantId, UUID actorId, UUID taskId, UUID ticketId, String authorization);
}
