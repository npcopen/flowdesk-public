package com.flowdesk.ticket.model.sla;

/**
 * 单个候选处理结果，供统计触发、跳过和索引变化。
 */
public record SlaProcessResult(boolean triggered, boolean committed, String code) {
}
