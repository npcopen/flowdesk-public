package com.flowdesk.ticket.model.reverse;

import java.util.UUID;

/**
 * 来源对应恢复身份，供重复处理核验恢复锚点。
 */
public record RecoveryIdentity(UUID recoveryId, byte[] intent, String state,
                                int attemptCount, String errorCode, String summary) { }
