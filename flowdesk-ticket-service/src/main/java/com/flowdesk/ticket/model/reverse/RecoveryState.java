package com.flowdesk.ticket.model.reverse;

import java.util.UUID;

/**
 * 消息恢复状态快照，供重放受理与发布结算。
 */
public record RecoveryState(UUID recoveryId, String state, long lockVersion) { }
