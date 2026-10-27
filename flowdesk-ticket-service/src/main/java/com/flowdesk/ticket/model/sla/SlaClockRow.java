package com.flowdesk.ticket.model.sla;

import java.time.Instant;
import java.util.UUID;

/**
 * 时钟持久化快照，保存策略时间、周期、达成结果与锁版本。
 */
public record SlaClockRow(UUID slaClockId, UUID ticketId, UUID slaPolicyId, String clockType,
                          String status, Instant startedAt, Instant deadlineAt,
                          Instant reminderDueAt, long policyDurationSeconds,
                          Long policyReminderBeforeSeconds, Instant milestoneAt,
                          Instant breachedAt, Instant satisfiedAt, long lockVersion, int cycleNo) {
}
