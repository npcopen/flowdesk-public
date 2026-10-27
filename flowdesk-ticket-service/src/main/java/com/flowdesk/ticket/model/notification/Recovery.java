package com.flowdesk.ticket.model.notification;

import java.util.UUID;

/**
 * 恢复记录快照，包含来源、错误、处理状态与重放租约。
 */
public record Recovery(UUID recoveryId, byte[] intent, String state, int attemptCount,
                        long lockVersion) { }
