package com.flowdesk.notification.model;
import java.util.*;
import java.time.Instant;

/**
 * 通知持久化行快照，保存数据库查询得到的业务字段，供服务校验与响应映射使用。
 */
public record NotificationAuditRow(UUID auditId, String actionCode, String targetType, UUID targetId,
                           String outcome, String actorType, UUID actorId, String reason,
                           String errorCode, UUID correlationId, UUID causationId,
                           Instant occurredAt, Instant recordedAt) { }
