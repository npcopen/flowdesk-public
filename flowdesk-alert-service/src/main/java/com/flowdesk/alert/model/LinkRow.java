package com.flowdesk.alert.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 故障与工单异步关联的数据库快照，记录建单意图、关联进度、重试信息、权威工单结果及版本。
 */
public record LinkRow(
            UUID incidentId,
            byte[] createIntent,
            String status,
            UUID ticketId,
            Instant requestedAt,
            Instant establishedAt,
            Instant recoveryRequiredAt,
            String recoveryReasonCode,
            int retryCount,
            Instant lastAttemptAt,
            long lockVersion,
            Instant createdAt,
            Instant updatedAt) {}
