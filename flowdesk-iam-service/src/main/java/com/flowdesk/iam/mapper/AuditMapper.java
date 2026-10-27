package com.flowdesk.iam.mapper;

import com.flowdesk.iam.model.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.*;

/**
 * IAM 操作审计的数据访问接口，负责按租户写入审计及读取已记录的原因。
 * 业务操作类型、结果和幂等判断由服务层组织，本接口只保存或查询对应审计事实。
 */
@Mapper
public interface AuditMapper {

    /**
     * 写入一条租户审计，保存操作对象、执行人、结果、原因及关联 ID。
     * occurredAt 表示业务发生时间，实际记录时间由数据库 CURRENT_TIMESTAMP(6) 生成。
     */
    void insertAudit(@Param("tenantId") UUID tenantId, @Param("auditId") UUID auditId, @Param("actionCode") String actionCode, @Param("targetType") String targetType, @Param("targetId") UUID targetId, @Param("outcome") String outcome, @Param("actorType") String actorType, @Param("actorId") UUID actorId, @Param("reason") String reason, @Param("errorCode") String errorCode, @Param("correlationId") UUID correlationId, @Param("occurredAt") Instant occurredAt);

    /** 按租户和审计 ID 读取已保存的原因；记录不存在或原因为空时返回空 Optional。 */
    Optional<String> findAuditReason(@Param("tenantId") UUID tenantId, @Param("auditId") UUID auditId);

    /**
     * 创建或授权操作发生重复键后，按既定审计 ID 再次读取原因，供服务层比对重复请求。
     * 与普通原因查询使用相同租户条件，不在 SQL 中执行额外的冲突处理。
     */
    Optional<String> findAuditReasonAfterDuplicate(@Param("tenantId") UUID tenantId, @Param("auditId") UUID auditId);
}
