package com.flowdesk.ticket.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 工单时间线快照，供业务事件读取及幂等效果核验。
 */
public record TimelineRow(UUID timelineId, UUID ticketId, byte[] effectKey, String eventType,
                          String summary, String relatedRefType, UUID relatedRefId,
                          String actorType, UUID actorId, Instant occurredAt,
                          UUID correlationId) {
}
