package com.flowdesk.ticket.model.reverse;

import java.time.Instant;
import java.util.UUID;

/**
 * 反向工作流处理所需工单与流程关联聚合，供事务内裁决。
 */
public record Aggregate(UUID ticketId, String ticketNo, String ticketStatus,
                        UUID definitionId, UUID versionId, Integer versionNo,
                        UUID requesterUserId, UUID assigneeUserId, Instant firstProcessableAt,
                        long ticketLockVersion,
                        byte[] startIntentKey,
                        String linkStatus, UUID workflowInstanceId, Instant requestedAt,
                        Instant establishedAt, long linkLockVersion, UUID slaPolicyId,
                        Long firstResponseSeconds, Long resolutionSeconds,
                        Long firstResponseReminderSeconds, Long resolutionReminderSeconds,
                        String policyStatus) { }
