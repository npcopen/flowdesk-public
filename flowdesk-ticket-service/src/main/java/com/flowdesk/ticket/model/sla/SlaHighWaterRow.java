package com.flowdesk.ticket.model.sla;

import java.time.Instant;
import java.util.UUID;

/**
 * SLA 变化高水位，以时间与时钟标识确定增量边界。
 */
public record SlaHighWaterRow(Instant updatedAt, UUID slaClockId) {
}
