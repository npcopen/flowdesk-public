package com.flowdesk.ticket.model.incident;

import java.util.UUID;

/**
 * 已创建工单身份与意图快照，供重复告警核验。
 */
public record TicketIdentity(UUID ticketId, byte[] intent, String originType, String ticketNo,
                              String subject, String description, String status) { }
