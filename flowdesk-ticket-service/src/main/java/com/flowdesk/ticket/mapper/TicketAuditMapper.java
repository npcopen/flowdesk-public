package com.flowdesk.ticket.mapper;

import com.flowdesk.ticket.model.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
/**
 * 工单审计持久化接口，记录操作主体、业务结果及请求链路信息。
 * 具体 SQL 由 MyBatis 提供；更新操作遵循对应语句的状态和版本条件。
 */
@Mapper
public interface TicketAuditMapper {
    /**
     * 简化审计写入入口，错误码和因果 ID 留空，并以业务发生时间作为记录时间。
     *
     * @param tenantId 数据所属租户标识
     */
    default void insertAudit(UUID tenantId, UUID auditId, String actionCode, String targetType, UUID targetId, String outcome, String actorType, UUID actorId, String reason, UUID correlationId, Instant occurredAt) {
        insertAudit(tenantId, auditId, actionCode, targetType, targetId, outcome, actorType,
                actorId, reason, null, correlationId, null, occurredAt, occurredAt);
    }

    /**
     * 写入工单操作审计，保存操作主体、结果、错误信息、关联链路及业务发生时间和记录时间。
     *
     * @param tenantId 数据所属租户标识
     */
    void insertAudit(
            @Param("tenantId") UUID tenantId,
            @Param("auditId") UUID auditId,
            @Param("actionCode") String actionCode,
            @Param("targetType") String targetType,
            @Param("targetId") UUID targetId,
            @Param("outcome") String outcome,
            @Param("actorType") String actorType,
            @Param("actorId") UUID actorId,
            @Param("reason") String reason,
            @Param("errorCode") String errorCode,
            @Param("correlationId") UUID correlationId,
            @Param("causationId") UUID causationId,
            @Param("occurredAt") Instant occurredAt,
            @Param("recordedAt") Instant recordedAt);

    /**
     * 按租户和审计 ID 读取审计详情，供查询操作结果或验证重复操作使用。
     *
     * @param tenantId 数据所属租户标识
     * @return 匹配记录；不存在时返回空 Optional
     */
    Optional<AuditRow> findAudit(@Param("tenantId") UUID tenantId, @Param("auditId") UUID auditId);

    /**
     * 读取审计记录中的原因字段；审计记录不存在或原因为空时返回空 Optional。
     *
     * @param tenantId 数据所属租户标识
     * @return 审计原因；记录不存在或原因为空时返回空 Optional
     */
    default Optional<String> findAuditReason(UUID tenantId, UUID auditId) {
        return findAudit(tenantId, auditId).map(AuditRow::reason);
    }
}
