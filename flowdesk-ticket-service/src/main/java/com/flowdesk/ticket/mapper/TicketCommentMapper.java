package com.flowdesk.ticket.mapper;

import com.flowdesk.ticket.model.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
/**
 * 工单评论持久化接口，负责评论写入、详情查询及按时间游标分页。
 * 具体 SQL 由 MyBatis 提供；更新操作遵循对应语句的状态和版本条件。
 */
@Mapper
public interface TicketCommentMapper {
    /**
     * 保存工单评论正文及作者、关联请求和创建时间。
     *
     * @param tenantId 数据所属租户标识
     */
    void insertComment(
            @Param("tenantId") UUID tenantId,
            @Param("commentId") UUID commentId,
            @Param("ticketId") UUID ticketId,
            @Param("body") String body,
            @Param("correlationId") UUID correlationId,
            @Param("authorId") UUID authorId,
            @Param("now") Instant now);

    /**
     * 按租户和评论 ID 查询评论，返回正文、所属工单及作者信息。
     *
     * @param tenantId 数据所属租户标识
     * @return 匹配记录；不存在时返回空 Optional
     */
    Optional<CommentRow> findComment(@Param("tenantId") UUID tenantId, @Param("commentId") UUID commentId);

    /**
     * 查询指定工单的评论，以创建时间和评论 ID 组成稳定游标，按指定方向排序并多取一条。
     *
     * @param tenantId 数据所属租户标识
     * @param pageSize 本页期望条数，SQL 会额外读取一条判断下一页
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<CommentRow> findComments(
            @Param("tenantId") UUID tenantId,
            @Param("ticketId") UUID ticketId,
            @Param("ascending") boolean ascending,
            @Param("cursorValue") Object cursorValue,
            @Param("cursorId") UUID cursorId,
            @Param("pageSize") int pageSize);
}
