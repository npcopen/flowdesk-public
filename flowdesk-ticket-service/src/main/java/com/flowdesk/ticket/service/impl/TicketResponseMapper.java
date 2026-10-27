package com.flowdesk.ticket.service.impl;

import com.flowdesk.ticket.mapper.*;
import com.flowdesk.ticket.model.*;
import com.flowdesk.ticket.persistence.Uuids;

import com.flowdesk.ticket.dto.TicketDtos;


/** 将持久化工单事实映射为统一 API 响应，供多个查询和命令用例复用。 */
final class TicketResponseMapper {
    /**
     * 私有构造方法，阻止工具类实例化，调用方应使用静态入口。
     */
    private TicketResponseMapper() {
    }

    /**
     * 将内部持久化快照转换为对外响应，只暴露接口契约需要的字段。
     */
    static TicketDtos.TicketResponse toResponse(TicketRow row) {
        TicketDtos.WorkflowRef workflowRef = row.workflowDefinitionId() == null ? null
                : new TicketDtos.WorkflowRef(row.workflowDefinitionId(), row.workflowVersionId(),
                        row.workflowVersionNo());
        return new TicketDtos.TicketResponse(row.ticketId(), row.ticketNo(), row.originType(),
                row.catalogItemId(), row.requesterUserId(), row.previousAttemptTicketId(),
                row.subject(), row.description(), row.status(), row.assigneeUserId(), workflowRef,
                row.firstProcessableAt(), row.resolvedAt(), row.closedAt(), row.lastReopenedAt(),
                row.reopenCount(), row.lockVersion(), row.createdAt(), row.updatedAt());
    }

    /**
     * 将内部持久化快照转换为对外响应，只暴露接口契约需要的字段。
     */
    static TicketDtos.TicketCommentResponse toResponse(CommentRow row) {
        return new TicketDtos.TicketCommentResponse(row.commentId(), row.ticketId(), row.body(),
                row.authorUserId(), row.createdAt(), row.correlationId());
    }

    /**
     * 将内部持久化快照转换为对外响应，只暴露接口契约需要的字段。
     */
    static TicketDtos.TimelineEntryResponse toResponse(TimelineRow row) {
        return new TicketDtos.TimelineEntryResponse(row.timelineId(), row.ticketId(), row.eventType(),
                row.summary(), row.relatedRefType(), row.relatedRefId(), row.actorType(), row.actorId(),
                row.occurredAt(), row.correlationId());
    }
}
