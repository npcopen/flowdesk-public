package com.flowdesk.ticket.mapper;

import com.flowdesk.ticket.model.*;
import com.flowdesk.ticket.dto.CatalogWriteRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 服务目录条目的持久化接口，负责目录查询、局部更新和审批流程配置落库。
 * 具体 SQL 由 MyBatis 提供；更新操作遵循对应语句的状态和版本条件。
 */
@Mapper
public interface CatalogMapper {

    /**
     * 按状态、风险等级筛选目录条目，以名称或编码和条目 ID 组成游标排序，多取一条判断下一页。
     *
     * @param tenantId 数据所属租户标识
     * @param pageSize 本页期望条数，SQL 会额外读取一条判断下一页
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<CatalogItemRow> findCatalogItems(
            @Param("tenantId") UUID tenantId,
            @Param("status") String status,
            @Param("riskLevel") String riskLevel,
            @Param("sortColumn") String sortColumn,
            @Param("ascending") boolean ascending,
            @Param("cursorValue") String cursorValue,
            @Param("cursorId") UUID cursorId,
            @Param("pageSize") int pageSize);

    /**
     * 按租户和目录条目 ID 查询目录详情，包含审批流程引用和 SLA 策略关联。
     *
     * @param tenantId 数据所属租户标识
     * @return 匹配记录；不存在时返回空 Optional
     */
    Optional<CatalogItemRow> findCatalogItem(@Param("tenantId") UUID tenantId, @Param("catalogItemId") UUID catalogItemId);

    /**
     * 查询目录详情并锁定该行，供同一事务内校验和修改配置使用。
     * 查询使用 FOR UPDATE 行锁，应在调用方事务内执行。
     *
     * @param tenantId 数据所属租户标识
     * @return 匹配记录；不存在时返回空 Optional
     */
    Optional<CatalogItemRow> findCatalogItemForUpdate(@Param("tenantId") UUID tenantId, @Param("catalogItemId") UUID catalogItemId);

    /**
     * 按预期版本局部更新目录；descriptionPresent 区分未传描述与显式清空，其他可选字段为空时保留原值。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenantId 数据所属租户标识
     * @param expectedLockVersion 调用方读取到的预期锁版本
     * @return 数据库报告的受影响行数
     */
    int updateCatalogItem(
            @Param("tenantId") UUID tenantId,
            @Param("catalogItemId") UUID catalogItemId,
            @Param("expectedLockVersion") long expectedLockVersion,
            @Param("itemName") String itemName,
            @Param("description") String description,
            @Param("descriptionPresent") boolean descriptionPresent,
            @Param("slaPolicyId") UUID slaPolicyId,
            @Param("status") String status,
            @Param("actorId") UUID actorId,
            @Param("now") Instant now);

    /**
     * 保存完整目录配置；存在工作流引用时启用审批，否则清空流程引用并初始化版本。
     *
     * @param tenant 数据所属租户标识
     */
    void insertCatalogConfiguration(
            @Param("tenant") UUID tenant,
            @Param("id") UUID id,
            @Param("r") CatalogWriteRequest r,
            @Param("actor") UUID actor,
            @Param("now") Instant now);

    /**
     * 按预期版本覆盖目录配置，根据工作流引用设置审批开关及流程版本，并递增锁版本。
     * WHERE 同时校验锁版本；受影响行数为 0 时，调用方需处理记录缺失或并发状态变化。
     *
     * @param tenant 数据所属租户标识
     * @return 数据库报告的受影响行数
     */
    int updateCatalogConfiguration(
            @Param("tenant") UUID tenant,
            @Param("id") UUID id,
            @Param("r") CatalogWriteRequest r,
            @Param("version") long version,
            @Param("actor") UUID actor,
            @Param("now") Instant now);
}
