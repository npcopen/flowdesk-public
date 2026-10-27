package com.flowdesk.ticket.model.sla;

import java.time.Instant;
import java.util.UUID;

/**
 * 升级接收目标快照，保存状态及配置身份。
 */
public record SlaEscalationTargetRow(UUID targetId, UUID recipientUserId, String purpose,
                                     String status, Instant effectiveAt,
                                     Long qualificationVersion, Instant qualificationCheckedAt,
                                     Instant qualificationValidUntil, String qualificationState,
                                     String qualificationReasonCode, long lockVersion,
                                     Instant createdAt, Instant updatedAt) {
}
