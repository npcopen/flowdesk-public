package com.flowdesk.ticket.model.sla;

import java.time.Instant;
import java.util.UUID;

/**
 * 阶段触发持久化快照，供展示与触发去重。
 */
public record SlaTriggerRow(UUID slaClockId, String stage, Instant dueAt, Instant triggeredAt) {
}
