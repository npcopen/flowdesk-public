package com.flowdesk.ticket.mapper;

import com.flowdesk.ticket.model.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SLA 策略查询接口，提供租户内策略详情、启用策略和游标分页列表。
 * 具体 SQL 由 MyBatis 提供；更新操作遵循对应语句的状态和版本条件。
 */
@Mapper
public interface SlaPolicyMapper {

    /**
     * 读取指定租户的 SLA 策略及响应、解决时限，不限制策略启用状态。
     *
     * @param tenantId 数据所属租户标识
     * @return 匹配记录；不存在时返回空 Optional
     */
    Optional<SlaPolicyRow> findSlaPolicy(@Param("tenantId") UUID tenantId, @Param("slaPolicyId") UUID slaPolicyId);

    /**
     * 读取指定租户中状态为 ACTIVE 的 SLA 策略，供业务绑定有效策略。
     *
     * @param tenantId 数据所属租户标识
     * @return 匹配记录；不存在时返回空 Optional
     */
    Optional<SlaPolicyRow> findActiveSlaPolicy(@Param("tenantId") UUID tenantId, @Param("slaPolicyId") UUID slaPolicyId);

    /**
     * 按可选状态筛选策略，以名称或编码和策略 ID 组成游标分页，多取一条判断下一页。
     *
     * @param tenantId 数据所属租户标识
     * @param pageSize 本页期望条数，SQL 会额外读取一条判断下一页
     * @return 符合条件的结果列表；无匹配记录时为空列表
     */
    List<SlaPolicyRow> findSlaPolicies(
            @Param("tenantId") UUID tenantId,
            @Param("status") String status,
            @Param("sortColumn") String sortColumn,
            @Param("ascending") boolean ascending,
            @Param("cursorValue") String cursorValue,
            @Param("cursorId") UUID cursorId,
            @Param("pageSize") int pageSize);
}
