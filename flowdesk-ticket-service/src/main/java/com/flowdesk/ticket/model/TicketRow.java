package com.flowdesk.ticket.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 工单主记录快照，包含生命周期、人员、规则引用与锁版本。
 */
public record TicketRow(UUID ticketId, String ticketNo, byte[] creationIntentKey,
                        String originType, UUID catalogItemId, UUID requesterUserId,
                        UUID previousAttemptTicketId, String subject, String description,
                        String status, UUID assigneeUserId, UUID workflowDefinitionId,
                        UUID workflowVersionId, Integer workflowVersionNo,
                        Instant firstProcessableAt, Instant resolvedAt, Instant closedAt,
                        Instant lastReopenedAt, int reopenCount, long lockVersion,
                        Instant createdAt, Instant updatedAt) {
}
