package com.flowdesk.ticket.model.sla;

import java.time.Instant;
import java.util.UUID;

/**
 * 数据库 SLA 候选快照，包含游标位置和当前时钟状态。
 */
public record SlaCandidateRow(UUID slaClockId, UUID ticketId, String status, String clockType,
                              Instant deadlineAt, Instant reminderDueAt, Instant updatedAt) {
}
