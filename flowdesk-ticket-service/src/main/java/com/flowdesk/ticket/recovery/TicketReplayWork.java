package com.flowdesk.ticket.recovery;
import java.time.Instant;
import java.util.UUID;
/**
 * 已受理的消息重放任务，保留原始身份、链路与执行所需数据。
 */
public record TicketReplayWork(UUID tenantId, UUID recoveryId, UUID sourceRecordId, UUID actorId,
                      String reason, UUID attemptCorrelationId, UUID sourceCorrelationId,
                      Instant leaseUntil) { }
