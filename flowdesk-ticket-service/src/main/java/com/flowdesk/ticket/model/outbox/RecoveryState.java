package com.flowdesk.ticket.model.outbox;

import java.time.Instant;

/**
 * 消息恢复状态快照，供重放受理与发布结算。
 */
public record RecoveryState(byte[] businessIntentKey, String state, int attemptCount,
                             Instant deadLetteredAt) {
}
