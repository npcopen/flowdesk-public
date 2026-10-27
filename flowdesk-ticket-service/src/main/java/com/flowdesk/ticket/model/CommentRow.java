package com.flowdesk.ticket.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 评论持久化快照，保留作者、内容及时间。
 */
public record CommentRow(UUID commentId, UUID ticketId, String body, UUID authorUserId,
                         Instant createdAt, UUID correlationId) {
}
