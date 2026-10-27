package com.flowdesk.ticket.notification.impl;

import com.flowdesk.ticket.notification.*;
import com.flowdesk.ticket.model.notification.IntentIdentity;
import org.springframework.stereotype.Service;
import com.flowdesk.ticket.persistence.Uuids;
import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.notification.NotificationWireCodec;
import com.flowdesk.common.tenant.TenantContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 在业务事务内写入通知意图，校验唯一身份与不可变内容。 */
@Service
public final class TicketNotificationOutboxWriterImpl implements TicketNotificationOutboxWriter {
    private static final NotificationEventContract.Binding BINDING = NotificationEventContract.TICKET;
    private final TicketNotificationOutboxPersistenceMapper mapper;
    /**
     * 构造工单通知组件并保存调用依赖。
     */
    public TicketNotificationOutboxWriterImpl(TicketNotificationOutboxPersistenceMapper mapper) { this.mapper = mapper; }

    /**
     * 保存业务发布意图，由持久化约束保证去重与内容一致。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 传入租户必须与当前上下文一致。
     * 唯一约束竞争时回读既有事实并核验内容，避免重复产生业务效果。
     */
    public void write(UUID tenantId, TicketNotificationIntent.Intent intent) {
        requireTransaction();
        requireTenant(tenantId);
        try {
            mapper.insertIntentUpdate(Uuids.toBytes(tenantId), Uuids.toBytes(intent.outboxId()), Uuids.toBytes(intent.messageId()), intent.publicationKey(), intent.businessIntentKey(), Uuids.toBytes(intent.sourceRefId()), BINDING.contractRef(), NotificationEventContract.VERSION, intent.bodyBytes(), intent.bodySha256(), Uuids.toBytes(intent.correlationId()), Uuids.toBytes(intent.causationId()), ts(intent.occurredAt()), ts(intent.occurredAt()), ts(intent.occurredAt()));
        }
        catch (DuplicateKeyException duplicate) {
            IntentIdentity existing = mapper.insertIntentSelect2(Uuids.toBytes(tenantId), intent.publicationKey())
                    .stream().findFirst().orElseThrow(() -> duplicate);
            if (!sameIntent(existing, intent)) {
                throw new IllegalStateException("Conflicting Ticket Notification outbox identity", duplicate);
            }
        }
    }

    /**
     * 比较规范业务意图，阻止同一身份对应不同载荷。
     */
    private static boolean sameIntent(IntentIdentity row, TicketNotificationIntent.Intent intent) {
        return intent.outboxId().equals(row.outboxId())
                && intent.messageId().equals(row.messageId())
                && Arrays.equals(intent.publicationKey(), row.publicationKey())
                && Arrays.equals(intent.businessIntentKey(), row.businessIntentKey())
                && "NOTIFICATION_REQUEST".equals(row.aggregateType())
                && intent.sourceRefId().equals(row.aggregateId())
                && BINDING.contractRef().equals(row.contractRef())
                && NotificationEventContract.VERSION == row.contractVersion()
                && Arrays.equals(intent.bodyBytes(), row.bodyBytes())
                && Arrays.equals(intent.bodySha256(), row.bodySha256());
    }

    /**
     * 解析时间文本或转换数据库时间值，遵循空值约定并保留业务精度。
     */
    private static Timestamp ts(Instant value) { return Timestamp.from(Objects.requireNonNull(value)); }

    /**
     * 要求调用处于数据库事务中，保证关联事实可以原子提交或回滚。
     */
    private static void requireTransaction() { if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Ticket Notification Outbox mutation requires a transaction"); }

    /**
     * 校验传入租户与当前上下文一致，阻止跨租户持久化操作。
     */
    private static void requireTenant(UUID id) { TenantContextHolder.current().requireTenant(id); }
}
