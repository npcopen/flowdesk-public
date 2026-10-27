package com.flowdesk.notification.service.impl;
import com.flowdesk.notification.service.NotificationAuditWriter;
import com.flowdesk.notification.mapper.NotificationAuditMapper;
import static com.flowdesk.notification.service.NotificationPersistenceSupport.*;
import com.flowdesk.notification.model.*;



import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 为用户命令与消息消费写入稳定审计锚点，重复插入时核验不可变内容。 */
@Component
public class NotificationAuditWriterImpl implements NotificationAuditWriter {
    private final NotificationAuditMapper mapper;

    /**
     * 注入运行时审计映射，为通知副作用提供按稳定审计标识写入及唯一键冲突内容核验。
     */
    public NotificationAuditWriterImpl(
            @Qualifier("notificationAuditMapper") NotificationAuditMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** 审计锚点只追加且标识稳定，重试时按同一内容幂等写入。 */
    public void insertOnce(UUID tenantId, UUID auditId, String actionCode,
                                 String targetType, UUID targetId, String outcome,
                                 String actorType, UUID actorId, String reason,
                                 String errorCode, UUID correlationId, UUID causationId,
                                 Instant occurredAt) {
        requireTenant(tenantId);
        requireTransaction();
        Optional<NotificationAuditRow> existing = mapper.findAudit(tenantId, auditId).stream().findFirst();
        if (existing.isPresent()) {
            if (!sameAudit(existing.get(), auditId, actionCode, targetType, targetId, outcome,
                    actorType, actorId, reason, errorCode, correlationId, causationId)) {
                throw new IllegalStateException("Notification audit identity/content conflict");
            }
            return;
        }
        try {
            mapper.insertAudit(tenantId, auditId, actionCode, targetType, targetId, outcome,
                    actorType, actorId, reason, errorCode, correlationId, causationId, occurredAt);
        }
        catch (DuplicateKeyException duplicate) {
            NotificationAuditRow raced = mapper.findAudit(tenantId, auditId).stream().findFirst()
                    .orElseThrow(() -> duplicate);
            if (!sameAudit(raced, auditId, actionCode, targetType, targetId, outcome,
                    actorType, actorId, reason, errorCode, correlationId, causationId)) {
                throw new IllegalStateException("Notification audit identity/content conflict", duplicate);
            }
        }
    }

    /**
     * 比较审计的稳定字段，判断唯一键冲突是否来自相同已提交事实。
     */
    private static boolean sameAudit(NotificationAuditRow row, UUID auditId,
                                     String actionCode, String targetType, UUID targetId,
                                     String outcome, String actorType, UUID actorId,
                                     String reason, String errorCode, UUID correlationId,
                                     UUID causationId) {
        return Objects.equals(row.auditId(), auditId)
                && Objects.equals(row.actionCode(), actionCode)
                && Objects.equals(row.targetType(), targetType)
                && Objects.equals(row.targetId(), targetId)
                && Objects.equals(row.outcome(), outcome)
                && Objects.equals(row.actorType(), actorType)
                && Objects.equals(row.actorId(), actorId)
                && Objects.equals(row.reason(), reason)
                && Objects.equals(row.errorCode(), errorCode)
                && Objects.equals(row.correlationId(), correlationId)
                && Objects.equals(row.causationId(), causationId);
    }

}
