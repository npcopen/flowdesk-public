package com.flowdesk.alert.mapper;

import com.flowdesk.alert.model.*;
import com.flowdesk.common.tenant.TenantContextHolder;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 告警操作审计持久化接口，保存操作目标、结果与消息追踪信息。
 * 业务入口先校验租户上下文，再调用 XML SQL 写入审计。
 */
public interface AlertAuditMapper {
    /**
     * 校验当前租户后，插入告警审计，保存动作、目标、结果、操作主体及关联和因果标识，发生与入库时间均使用 now。
     */
    default void insertAudit(UUID tenantId, UUID auditId, String action, String targetType, UUID targetId, String outcome, String reason, String error, String actorType, UUID actorId, UUID correlationId, UUID causationId, Instant now) {
        TenantContextHolder.current().requireTenant(tenantId);
        sqlInsertAudit(tenantId, auditId, action, targetType, targetId, outcome, reason, error, actorType, actorId, correlationId, causationId, now);
    }
    /**
     * 插入告警审计，保存动作、目标、结果、操作主体及关联和因果标识，发生与入库时间均使用 now。
     */
    void sqlInsertAudit(
            @Param("tenantId") UUID tenantId,
            @Param("auditId") UUID auditId,
            @Param("action") String action,
            @Param("targetType") String targetType,
            @Param("targetId") UUID targetId,
            @Param("outcome") String outcome,
            @Param("reason") String reason,
            @Param("error") String error,
            @Param("actorType") String actorType,
            @Param("actorId") UUID actorId,
            @Param("correlationId") UUID correlationId,
            @Param("causationId") UUID causationId,
            @Param("now") Instant now);
}
