package com.flowdesk.ticket.model.incident;

import java.time.Instant;
import java.util.UUID;

/**
 * 时间线身份与内容快照，供相同消息副作用幂等核验。
 */
public record TimelineIdentity(UUID timelineId, String eventType, String summary,
                                String relatedRefType, UUID relatedRefId, String actorType,
                                UUID actorId, Instant occurredAt, UUID correlationId,
                                UUID causationId, byte[] effectKey) { }
