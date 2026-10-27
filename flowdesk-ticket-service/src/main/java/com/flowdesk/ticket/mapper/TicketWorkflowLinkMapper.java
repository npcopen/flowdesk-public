package com.flowdesk.ticket.mapper;

import com.flowdesk.ticket.model.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
/**
 * 工单与工作流启动请求的关联接口，维护请求状态及流程实例关联信息。
 * 具体 SQL 由 MyBatis 提供；更新操作遵循对应语句的状态和版本条件。
 */
@Mapper
public interface TicketWorkflowLinkMapper {
    /**
     * 查询指定工单的流程启动关联，返回请求状态、流程实例、恢复信息及锁版本。
     *
     * @param tenantId 数据所属租户标识
     * @return 匹配记录；不存在时返回空 Optional
     */
    Optional<WorkflowStartRow> findWorkflowStartLink(@Param("tenantId") UUID tenantId, @Param("ticketId") UUID ticketId);

    /**
     * 为指定工单创建 REQUESTED 状态的流程启动关联，保存启动意图键和请求时间。
     *
     * @param tenantId 数据所属租户标识
     */
    void insertWorkflowStartLink(
            @Param("tenantId") UUID tenantId,
            @Param("ticketId") UUID ticketId,
            @Param("startIntentKey") byte[] startIntentKey,
            @Param("requestedAt") Instant requestedAt,
            @Param("correlationId") UUID correlationId);
}
