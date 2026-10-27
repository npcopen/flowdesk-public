package com.flowdesk.alert.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 告警事件列表的不可变筛选条件；时间、事件类型、集成及处理结论共同参与查询和游标签名作用域。
 */
public record EventFilter(
            String eventType,
            String disposition,
            UUID incidentId,
            String fingerprint,
            String cycleRef,
            String externalEventId,
            Instant receivedFrom,
            Instant receivedTo) {}
