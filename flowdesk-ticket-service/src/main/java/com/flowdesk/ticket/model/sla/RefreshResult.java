package com.flowdesk.ticket.model.sla;

import java.util.UUID;

/**
 * 资格刷新结果，保留本轮租户、目标与成功失败统计。
 */
public record RefreshResult(UUID tenantId, boolean eligible, String code) { }
