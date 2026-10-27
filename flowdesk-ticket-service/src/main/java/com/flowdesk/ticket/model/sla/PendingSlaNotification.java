package com.flowdesk.ticket.model.sla;

import java.util.UUID;

/**
 * 已触发但仍需通知的 SLA 事实候选，供后台补偿。
 */
public record PendingSlaNotification(UUID triggerId, UUID clockId, UUID ticketId, String stage) { }
