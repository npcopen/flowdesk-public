package com.flowdesk.alert.model;
import java.util.*;
import java.time.Instant;

/**
 * 人工重放所需的原 Inbox 快照，包含契约、原始正文、摘要及消息追踪字段，供恢复原消费者输入。
 */
public record AlertRecoveryInbox(
            UUID inboxId,
            UUID messageId,
            byte[] intent,
            String contractRef,
            int contractVersion,
            byte[] body,
            byte[] hash,
            UUID correlation,
            UUID causation,
            Instant occurred,
            String status,
            long version) {}
