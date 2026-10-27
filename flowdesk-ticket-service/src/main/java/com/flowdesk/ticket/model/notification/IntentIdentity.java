package com.flowdesk.ticket.model.notification;

import java.util.UUID;

/**
 * 通知发布意图身份快照，供重试时比较原始业务内容。
 */
public record IntentIdentity(UUID outboxId, UUID messageId, byte[] publicationKey,
                              byte[] businessIntentKey, String aggregateType, UUID aggregateId,
                              String contractRef, int contractVersion, byte[] bodyBytes,
                              byte[] bodySha256, UUID correlationId, UUID causationId) { }
