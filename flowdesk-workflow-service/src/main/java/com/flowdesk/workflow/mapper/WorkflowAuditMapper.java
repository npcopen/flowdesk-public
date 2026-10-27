package com.flowdesk.workflow.mapper;

import com.flowdesk.workflow.model.persistence.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 工作流人工操作审计的持久化接口。
 * 按租户保存、读取审计事实，保留操作人、执行结果及关联链路；具体 SQL 定义在同名 XML 中。
 */
@Mapper
public interface WorkflowAuditMapper {
    /**
     * 保存一次工作流人工操作的审计事实。
     * 记录租户、操作对象、操作人、执行结果、原因和 correlationId/causationId，发生时间使用调用方传入的 now。
     */
    void insertAudit(@Param("tenantId") UUID tenantId, @Param("auditId") UUID auditId, @Param("actionCode") String actionCode, @Param("targetType") String targetType, @Param("targetId") UUID targetId, @Param("outcome") String outcome, @Param("actorId") UUID actorId, @Param("reason") String reason, @Param("errorCode") String errorCode, @Param("correlationId") UUID correlationId, @Param("causationId") UUID causationId, @Param("now") Instant now);

    /**
     * 按租户和审计标识读取审计明细。
     * 返回操作对象、执行结果和关联链路等原始事实；不存在时返回空 Optional。
     */
    Optional<AuditRow> findAudit(@Param("tenantId") UUID tenantId, @Param("auditId") UUID auditId);
}
