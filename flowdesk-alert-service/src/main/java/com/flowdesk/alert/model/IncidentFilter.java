package com.flowdesk.alert.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 故障列表的筛选条件，描述状态、工单关联状态及开启时间范围，随分页游标一起绑定。
 */
public record IncidentFilter(
            String status,
            String fingerprint,
            String ticketLinkStatus,
            Instant openedFrom,
            Instant openedTo) {}
