package com.flowdesk.ticket.model.incident;

import java.time.Instant;
import java.util.UUID;

/**
 * 既有 SLA 时钟身份与策略快照，供重复消费一致性核验。
 */
public record ClockIdentity(UUID clockId, UUID policyId, String status, Instant startedAt,
                             Instant deadlineAt, Instant reminderDueAt, long duration, Long reminder) { }
