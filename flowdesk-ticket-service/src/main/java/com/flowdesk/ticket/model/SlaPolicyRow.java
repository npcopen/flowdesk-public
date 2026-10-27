package com.flowdesk.ticket.model;

import java.util.UUID;

/**
 * SLA 策略的不可变持久化投影，保留响应、解决时长、提醒配置及锁版本。
 */
public record SlaPolicyRow(UUID slaPolicyId, String policyCode, String policyName, long firstResponseSeconds, long resolutionSeconds, Long firstResponseReminderBeforeSeconds, Long resolutionReminderBeforeSeconds, String status, long lockVersion) {
}
