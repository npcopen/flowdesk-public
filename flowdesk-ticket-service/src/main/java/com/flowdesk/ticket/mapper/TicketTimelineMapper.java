package com.flowdesk.ticket.mapper;

import com.flowdesk.ticket.model.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
/**
 * 工单时间线持久化接口，保存业务事件、查询副作用记录并分页读取历史。
 * 具体 SQL 由 MyBatis 提供；更新操作遵循对应语句的状态和版本条件。
 */
@Mapper
public interface TicketTimelineMapper {
    /**
     * 简化时间线写入入口，因果 ID 留空，并以业务发生时间作为记录时间。
     *
     * @param tenantId 数据所属租户标识
     */
    default void insertTimeline(UUID tenantId, UUID timelineId, UUID ticketId, byte[] effectKey, String eventType, String summary, String relatedRefType, UUID relatedRefId, String actorType, UUID actorId, Instant occurredAt, UUID correlationId) {
        insertTimeline(tenantId, timelineId, ticketId, effectKey, eventType, summary,
                relatedRefType, relatedRefId, actorType, actorId, occurredAt, correlationId,
                null, occurredAt);
    }

    /**
     * 写入工单业务事件、关联对象、副作用键和链路信息，分别保留业务发生时间及实际记录时间。
     *
     * @param tenantId 数据所属租户标识
     */
    void insertTimeline(
            @Param("tenantId") UUID tenantId,
            @Param("timelineId") UUID timelineId,
            @Param("ticketId") UUID ticketId,
            @Param("effectKey") byte[] effectKey,
            @Param("eventType") String eventType,
            @Param("summary") String summary,
            @Param("relatedRefType") String relatedRefType,
            @Param("relatedRefId") UUID relatedRefId,
            @Param("actorType") String actorType,
            @Param("actorId") UUID actorId,
            @Param("occurredAt") Instant occurredAt,
            @Param("correlationId") UUID correlationId,
            @Param("causationId") UUID causationId,
            @Param("recordedAt") Instant recordedAt);

    /**
     * 按租户、工单和副作用键查找已有时间线记录，供重复业务效果校验。
     *
     * @param tenantId 数据所属租户标识
     * @return 匹配记录；不存在时返回空 Optional
     */
    Optional<TimelineRow> findTimelineByEffectKey(
            @Param("tenantId") UUID tenantId,
            @Param("ticketId") UUID ticketId,
            @Param("effectKey") byte[] effectKey);

    /**
     * 按事件类型及发生时间范围筛选工单时间线，以发生时间和时间线 ID 组成游标并多取一条。
     *
     * @param tenantId 数据所属租户标识
     * @param pageSize 本页期望条数，SQL 会额外读取一条判断下一页
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<TimelineRow> findTimelineEntries(
            @Param("tenantId") UUID tenantId,
            @Param("ticketId") UUID ticketId,
            @Param("eventType") String eventType,
            @Param("occurredFrom") Instant occurredFrom,
            @Param("occurredTo") Instant occurredTo,
            @Param("ascending") boolean ascending,
            @Param("cursorValue") Object cursorValue,
            @Param("cursorId") UUID cursorId,
            @Param("pageSize") int pageSize);
}
