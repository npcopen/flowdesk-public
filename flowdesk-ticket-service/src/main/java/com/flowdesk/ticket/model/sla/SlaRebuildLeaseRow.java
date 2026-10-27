package com.flowdesk.ticket.model.sla;

import java.time.Instant;
import java.util.UUID;

/**
 * 索引重建租约快照，记录所有者、阶段、高水位及截止时间。
 */
public record SlaRebuildLeaseRow(UUID tenantId, long fencingEpoch, UUID runId,
                                 Instant highWaterUpdatedAt, UUID highWaterSlaClockId,
                                 Instant expiresAt, String state, String lastErrorCode,
                                 long lockVersion, Instant createdAt, Instant updatedAt) {
}
