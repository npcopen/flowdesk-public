package com.flowdesk.alert.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 故障某一代周期的查询快照，包含活动或恢复状态、事件计数、时间及版本，用于聚合与视图生成。
 */
public record IncidentCycleRow(
            UUID incidentId,
            String fingerprint,
            int generationNo,
            String status,
            int eventCount,
            Instant openedAt,
            Instant lastEventAt,
            Instant recoveredAt,
            String summary,
            long lockVersion,
            Instant createdAt,
            Instant updatedAt,
            String cycleRef) {}
