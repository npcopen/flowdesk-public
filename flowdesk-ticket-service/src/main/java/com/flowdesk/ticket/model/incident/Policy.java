package com.flowdesk.ticket.model.incident;

import java.util.UUID;

/**
 * 告警建单租户策略快照，固定目录及 SLA 配置。
 */
public record Policy(UUID policyId, long firstResponseSeconds, long resolutionSeconds,
                     Long firstReminderSeconds, Long resolutionReminderSeconds) { }
