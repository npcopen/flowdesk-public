package com.flowdesk.notification.mapper;
import com.flowdesk.notification.model.*;

import org.apache.ibatis.annotations.Param;
import java.util.*;
import java.time.Instant;
import com.flowdesk.notification.event.*;
import com.flowdesk.notification.recovery.*;

/**
 * 通知操作审计持久化接口，按租户保存和读取审计结果。
 * 记录操作人、目标、失败原因及关联和因果标识，供通知和恢复操作追溯。
 */
public interface NotificationAuditMapper {
    /**
     * 按租户及审计标识读取通知审计，返回目标、操作人、结果与追踪信息。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<NotificationAuditRow> findAudit(@Param("tenantId") UUID tenantId, @Param("auditId") UUID auditId);
    /**
     * 保存通知操作动作、目标、主体、原因及追踪信息，发生与入库时间均使用 occurredAt。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int insertAudit(@Param("tenantId") UUID tenantId, @Param("auditId") UUID auditId, @Param("actionCode") String actionCode, @Param("targetType") String targetType, @Param("targetId") UUID targetId, @Param("outcome") String outcome, @Param("actorType") String actorType, @Param("actorId") UUID actorId, @Param("reason") String reason, @Param("errorCode") String errorCode, @Param("correlationId") UUID correlationId, @Param("causationId") UUID causationId, @Param("occurredAt") Instant occurredAt);
}
