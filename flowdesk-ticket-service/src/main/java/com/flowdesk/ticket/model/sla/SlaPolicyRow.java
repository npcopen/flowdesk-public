package com.flowdesk.ticket.model.sla;

import java.util.UUID;

/**
 * SLA 策略快照，保留响应、解决时长与提醒配置。
 */
public record SlaPolicyRow(UUID slaPolicyId, String policyCode, String policyName,
                           long firstResponseSeconds, long resolutionSeconds,
                           Long firstResponseReminderBeforeSeconds,
                           Long resolutionReminderBeforeSeconds, String status,
                           long lockVersion) {
}
