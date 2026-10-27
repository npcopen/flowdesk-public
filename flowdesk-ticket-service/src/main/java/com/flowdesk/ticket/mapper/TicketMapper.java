package com.flowdesk.ticket.mapper;

import com.flowdesk.ticket.model.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
/**
 * 工单主表持久化接口，覆盖创建、查询、状态流转和处理人变更。
 * 具体 SQL 由 MyBatis 提供；更新操作遵循对应语句的状态和版本条件。
 */
@Mapper
public interface TicketMapper {
    /**
     * 按请求人、处理人或可见范围统计待审批、待分配、已分配和处理中的工单数。
     *
     * @param tenantId 数据所属租户标识
     * @return 符合条件的记录数量
     */
    long countPendingTickets(@Param("tenantId") UUID tenantId, @Param("scope") TicketScope scope, @Param("actorId") UUID actorId);

    /**
     * 按租户和工单 ID 查询工单详情，包含状态、处理人、流程引用及锁版本。
     *
     * @param tenantId 数据所属租户标识
     * @return 匹配记录；不存在时返回空 Optional
     */
    Optional<TicketRow> findTicket(@Param("tenantId") UUID tenantId, @Param("ticketId") UUID ticketId);

    /**
     * 按租户和展示工单号查询工单详情。
     *
     * @param tenantId 数据所属租户标识
     * @return 匹配记录；不存在时返回空 Optional
     */
    Optional<TicketRow> findTicketByNumber(@Param("tenantId") UUID tenantId, @Param("ticketNo") String ticketNo);

    /**
     * 读取并锁定指定工单，供事务内进行状态校验和后续写入。
     * 查询使用 FOR UPDATE 行锁，应在调用方事务内执行。
     *
     * @param tenantId 数据所属租户标识
     * @return 匹配记录；不存在时返回空 Optional
     */
    Optional<TicketRow> findTicketForUpdate(@Param("tenantId") UUID tenantId, @Param("ticketId") UUID ticketId);

    /**
     * 按租户和创建意图键查询已有工单，供创建请求幂等校验使用。
     *
     * @param tenantId 数据所属租户标识
     * @return 匹配记录；不存在时返回空 Optional
     */
    Optional<TicketRow> findTicketByCreationIntent(@Param("tenantId") UUID tenantId, @Param("creationIntentKey") byte[] creationIntentKey);

    /**
     * 保存新工单并初始化终态时间、重开次数和锁版本；手工来源记录用户主体，其他来源记录服务主体。
     *
     * @param tenantId 数据所属租户标识
     */
    void insertTicket(
            @Param("tenantId") UUID tenantId,
            @Param("ticketId") UUID ticketId,
            @Param("ticketNo") String ticketNo,
            @Param("creationIntentKey") byte[] creationIntentKey,
            @Param("originType") String originType,
            @Param("catalogItemId") UUID catalogItemId,
            @Param("requesterUserId") UUID requesterUserId,
            @Param("previousAttemptTicketId") UUID previousAttemptTicketId,
            @Param("subject") String subject,
            @Param("description") String description,
            @Param("status") String status,
            @Param("assigneeUserId") UUID assigneeUserId,
            @Param("workflowDefinitionId") UUID workflowDefinitionId,
            @Param("workflowVersionId") UUID workflowVersionId,
            @Param("workflowVersionNo") Integer workflowVersionNo,
            @Param("firstProcessableAt") Instant firstProcessableAt,
            @Param("actorId") UUID actorId,
            @Param("correlationId") UUID correlationId,
            @Param("now") Instant now);

    /**
     * 按预期锁版本设置处理人并将工单置为 ASSIGNED，同时记录操作人并递增版本。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 数据所属租户标识
     * @param expectedLockVersion 调用方读取到的预期锁版本
     * @return 数据库报告的受影响行数
     */
    int updateTicketAssign(
            @Param("tenantId") UUID tenantId,
            @Param("ticketId") UUID ticketId,
            @Param("expectedLockVersion") long expectedLockVersion,
            @Param("assigneeUserId") UUID assigneeUserId,
            @Param("actorId") UUID actorId,
            @Param("now") Instant now);

    /**
     * 按预期锁版本将工单置为 IN_PROGRESS，记录受理操作人和更新时间。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 数据所属租户标识
     * @param expectedLockVersion 调用方读取到的预期锁版本
     * @return 数据库报告的受影响行数
     */
    int updateTicketAccept(
            @Param("tenantId") UUID tenantId,
            @Param("ticketId") UUID ticketId,
            @Param("expectedLockVersion") long expectedLockVersion,
            @Param("actorId") UUID actorId,
            @Param("now") Instant now);

    /**
     * 按预期锁版本更换处理人为 newAssigneeUserId，并将状态设为 ASSIGNED。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 数据所属租户标识
     * @param expectedLockVersion 调用方读取到的预期锁版本
     * @return 数据库报告的受影响行数
     */
    int updateTicketTransfer(
            @Param("tenantId") UUID tenantId,
            @Param("ticketId") UUID ticketId,
            @Param("expectedLockVersion") long expectedLockVersion,
            @Param("newAssigneeUserId") UUID newAssigneeUserId,
            @Param("actorId") UUID actorId,
            @Param("now") Instant now);

    /**
     * 按预期锁版本将工单置为 RESOLVED，仅在尚无解决时间时写入本次解决时间。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 数据所属租户标识
     * @param expectedLockVersion 调用方读取到的预期锁版本
     * @return 数据库报告的受影响行数
     */
    int updateTicketResolve(
            @Param("tenantId") UUID tenantId,
            @Param("ticketId") UUID ticketId,
            @Param("expectedLockVersion") long expectedLockVersion,
            @Param("actorId") UUID actorId,
            @Param("now") Instant now);

    /**
     * 按预期锁版本关闭工单，记录关闭时间与终态时间并递增版本。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 数据所属租户标识
     * @param expectedLockVersion 调用方读取到的预期锁版本
     * @return 数据库报告的受影响行数
     */
    int updateTicketClose(
            @Param("tenantId") UUID tenantId,
            @Param("ticketId") UUID ticketId,
            @Param("expectedLockVersion") long expectedLockVersion,
            @Param("actorId") UUID actorId,
            @Param("now") Instant now);

    /**
     * 按预期锁版本重开工单为 IN_PROGRESS，记录最近重开时间并累计重开次数。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 数据所属租户标识
     * @param expectedLockVersion 调用方读取到的预期锁版本
     * @return 数据库报告的受影响行数
     */
    int updateTicketReopen(
            @Param("tenantId") UUID tenantId,
            @Param("ticketId") UUID ticketId,
            @Param("expectedLockVersion") long expectedLockVersion,
            @Param("actorId") UUID actorId,
            @Param("now") Instant now);

    /**
     * 按可见范围、状态、来源、目录及创建时间筛选工单，以白名单排序字段和工单 ID 稳定分页，多取一条。
     *
     * @param tenantId 数据所属租户标识
     * @param pageSize 本页期望条数，SQL 会额外读取一条判断下一页
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<TicketRow> findTickets(
            @Param("tenantId") UUID tenantId,
            @Param("scope") TicketScope scope,
            @Param("scopeId") UUID scopeId,
            @Param("status") String status,
            @Param("originType") String originType,
            @Param("catalogItemId") UUID catalogItemId,
            @Param("createdFrom") Instant createdFrom,
            @Param("createdTo") Instant createdTo,
            @Param("sortColumn") String sortColumn,
            @Param("ascending") boolean ascending,
            @Param("cursorValue") Object cursorValue,
            @Param("cursorId") UUID cursorId,
            @Param("pageSize") int pageSize);
}
