package com.flowdesk.alert.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 告警事件的只读数据库投影，保留集成身份、原始正文摘要、接纳结论与故障周期关联信息。
 */
public record EventRow(
            UUID alertEventId,
            UUID integrationId,
            String integrationCode,
            String integrationName,
            String externalEventId,
            String eventType,
            String fingerprint,
            String cycleRef,
            Instant occurredAt,
            Instant receivedAt,
            String disposition,
            UUID incidentId,
            byte[] rawBodySha256,
            String summary,
            UUID correlationId) {}
