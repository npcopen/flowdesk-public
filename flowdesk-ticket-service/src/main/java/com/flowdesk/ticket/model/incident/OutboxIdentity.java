package com.flowdesk.ticket.model.incident;

import java.util.UUID;

/**
 * 结果发布身份与规范内容快照，供重复告警核验。
 */
public record OutboxIdentity(UUID outboxId, UUID messageId, byte[] publicationKey,
                              byte[] intent, UUID aggregateId, String contractRef,
                              int contractVersion, byte[] body, byte[] hash,
                              UUID correlationId, UUID causationId) { }
