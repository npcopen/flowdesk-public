package com.flowdesk.ticket.model.sla;

import java.time.Instant;
import java.util.UUID;

/**
 * 收件人资格快照，保存规则版本、校验时间与有效期。
 */
public record SlaQualificationRow(UUID targetId, UUID recipientUserId, String purpose,
                                  long qualificationVersion, Instant checkedAt,
                                  Instant validUntil, boolean eligible, String reasonCode) {
}
