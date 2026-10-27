package com.flowdesk.alert.model;
import java.util.*;
import java.time.Instant;

/**
 * 工单结果的持久化消费快照，保留消息和业务意图身份、原文摘要、处理结果及乐观锁版本。
 */
public record TicketResultInbox(
            UUID inboxId,
            byte[] intent,
            byte[] body,
            byte[] hash,
            String status,
            UUID result,
            long version) {}
