package com.flowdesk.alert.model;
import java.util.*;
import java.time.Instant;

/**
 * 成功领取发件箱后的原始发送数据和租约凭据；发布结算须使用本次 token、版本与尝试次数。
 */
public record AlertOutboxClaim(
            UUID tenantId,
            UUID outboxId,
            UUID messageId,
            byte[] publicationKey,
            byte[] intent,
            UUID incidentId,
            String contractRef,
            byte[] body,
            byte[] hash,
            UUID correlationId,
            UUID causationId,
            int attemptCount,
            String token,
            Instant lockedAt,
            long lockVersion,
            Instant createdAt) {}
