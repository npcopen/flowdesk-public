package com.flowdesk.notification.event;
import com.flowdesk.notification.service.NotificationInboxService;
import com.flowdesk.notification.model.*;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.notification.NotificationWireCodec;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.notification.mapper.NotificationRecoveryMapper;
import com.flowdesk.notification.service.NotificationRecoveryRegistrationService;
import com.flowdesk.notification.mapper.NotificationMapper;
import com.flowdesk.notification.model.NotificationRow;
import com.flowdesk.notification.service.NotificationAuditWriter;
import com.flowdesk.notification.service.NotificationSupport;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 处理通知主队列和死信，将 Inbox、通知副作用、恢复事实及审计在本地事务中一致落库。 */
@Service
public class NotificationConsumerService {
    private final NotificationMapper repository;
    private final NotificationInboxService inboxes;
    private final NotificationRecoveryMapper recoveries;
    private final NotificationRecoveryRegistrationService registration;
    private final NotificationAuditWriter audits;
    private final NotificationEnvelopeGuard guard;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 注入通知及恢复映射、Inbox 去重服务和审计写入器，使用运行时事务管理器原子提交消息副作用，并以时钟及守卫统一时间和信任边界。
     */
    public NotificationConsumerService(NotificationMapper repository,
            NotificationInboxService inboxes,
            @Qualifier("notificationRecoveryMapper") NotificationRecoveryMapper recoveries,
            NotificationRecoveryRegistrationService registration,
            NotificationAuditWriter audits, NotificationEnvelopeGuard guard,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.inboxes = Objects.requireNonNull(inboxes, "inboxes");
        this.registration = registration;
        this.recoveries = Objects.requireNonNull(recoveries, "recoveries");
        this.audits = Objects.requireNonNull(audits, "audits");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 主队列入口：无法信任租户的非法传输报文仅作 ACK 处置，不写入租户业务事实。 */
    public NotificationConsumeResult consume(NotificationTransportMessage delivery) {
        final AcceptedNotification message;
        try {
            message = guard.validateMain(delivery);
        }
        catch (NotificationContractViolation violation) {
            if (!violation.hasTrustedEnvelope()) {
                // 非法信封不建立租户上下文；此处 ACK 只代表传输层丢弃，不代表业务处理成功。
                return NotificationConsumeResult.ack(violation.code());
            }
            NotificationEnvelope envelope = violation.envelope().orElseThrow();
            TenantContext context = TenantContext.ofValidatedBrokerMessage(
                    envelope.tenantId(), envelope.binding().producerService(),
                    envelope.correlationId());
            try (TenantContextScope ignored = TenantContextScope.open(context)) {
                try {
                    NotificationConsumeResult result = transactions.execute(tx ->
                            rejectTrustedEnvelope(envelope, violation.code()));
                    return Objects.requireNonNull(result);
                }
                catch (DataAccessException | TransactionException | IllegalStateException transientFailure) {
                    // 可信租户内的拒绝也必须持久化为 Inbox 事实，只有拒绝事实及审计提交后才能 ACK。
                    return NotificationConsumeResult.retry("NOTIFICATION_DB_UNAVAILABLE");
                }
            }
        }
        TenantContext context = TenantContext.ofValidatedBrokerMessage(
                message.tenantId(), message.binding().producerService(), message.correlationId());
        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            try {
                NotificationConsumeResult result = transactions.execute(tx -> process(message));
                return Objects.requireNonNull(result);
            }
            catch (DataAccessException | TransactionException | IllegalStateException transientFailure) {
                return NotificationConsumeResult.retry("NOTIFICATION_DB_UNAVAILABLE");
            }
        }
    }

    /** 死信接纳入口，完整原消息持久化后才向 Broker 确认。 */
    public NotificationConsumeResult acceptDlq(NotificationTransportMessage delivery) {
        final AcceptedNotification message;
        try { message = guard.validateDlq(delivery); }
        catch (NotificationContractViolation poison) { return NotificationConsumeResult.ack(poison.code()); }
        TenantContext context = TenantContext.ofValidatedBrokerMessage(
                message.tenantId(), message.binding().producerService(), message.correlationId());
        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            try {
                NotificationConsumeResult result = transactions.execute(tx -> persistDlq(message));
                return Objects.requireNonNull(result);
            }
            catch (DataAccessException | TransactionException | IllegalStateException transientFailure) {
                return NotificationConsumeResult.retry("NOTIFICATION_DLQ_PERSISTENCE_UNAVAILABLE");
            }
        }
    }

    /**
     * 在本地事务核对重复死信，并保存 Inbox、恢复原始事实与相应审计。
     */
    private NotificationConsumeResult persistDlq(AcceptedNotification message) {
        Instant now = now();
        NotificationInboxDuplicate duplicate = inboxes.findDuplicate(message, true);
        if (duplicate.kind() == NotificationInboxDuplicateKind.TERMINAL) {
            return NotificationConsumeResult.ack("NOTIFICATION_DUPLICATE");
        }
        if (duplicate.kind() == NotificationInboxDuplicateKind.CONFLICT) {
            audits.insertOnce(message.tenantId(), auditId("FD-NOTIFICATION-DLQ-CONFLICT-AUDIT-V1",
                            message.tenantId(), message.messageId(), duplicate.conflictCode()),
                    "NOTIFICATION_INBOX_CONFLICT", "MESSAGE_RECOVERY", null, "REJECTED",
                    "SERVICE", null, "Message identity/content conflict", duplicate.conflictCode(),
                    message.correlationId(), message.causationId(), now);
            return NotificationConsumeResult.ack(duplicate.conflictCode());
        }
        if (duplicate.kind() == NotificationInboxDuplicateKind.RECOVERABLE) {
            // 仅有 RECOVERY_REQUIRED Inbox 还不足以确认死信；必须同时存在确定性 Recovery，缺失配对记录时保留 Broker 重试。
            requireDlqRecovery(message, duplicate.inbox());
            return NotificationConsumeResult.ack("NOTIFICATION_RECOVERY_ALREADY_RECORDED");
        }
        // 原消息标识是确定性的，死信重复投递不得再创建一组 Inbox、Recovery 或审计锚点。
        UUID inboxId = stableUuid(NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-INBOX-V1", message.tenantId(), message.messageId()));
        try {
            inboxes.insertRecoveryInbox(message, inboxId, "BROKER_DELIVERY_EXHAUSTED", now);
        }
        catch (DuplicateKeyException racedInsert) {
            NotificationInboxDuplicate raced = inboxes.findDuplicate(message, true);
            if (raced.kind() == NotificationInboxDuplicateKind.CONFLICT) {
                audits.insertOnce(message.tenantId(), auditId("FD-NOTIFICATION-DLQ-CONFLICT-AUDIT-V1",
                                message.tenantId(), message.messageId(), raced.conflictCode()),
                        "NOTIFICATION_INBOX_CONFLICT", "MESSAGE_RECOVERY", null, "REJECTED",
                        "SERVICE", null, "Message identity/content conflict", raced.conflictCode(),
                        message.correlationId(), message.causationId(), now);
                return NotificationConsumeResult.ack(raced.conflictCode());
            }
            if (raced.kind() == NotificationInboxDuplicateKind.TERMINAL) {
                return NotificationConsumeResult.ack("NOTIFICATION_DUPLICATE");
            }
            if (raced.kind() == NotificationInboxDuplicateKind.RECOVERABLE) {
                requireDlqRecovery(message, raced.inbox());
                return NotificationConsumeResult.ack("NOTIFICATION_RECOVERY_ALREADY_RECORDED");
            }
            throw racedInsert;
        }
        UUID recoveryId = stableUuid(NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-RECOVERY-V1", message.tenantId(), inboxId));
        registration.insertRecovery(message.tenantId(), recoveryId, inboxId,
                message.businessIntentKey(), "BROKER_DELIVERY_EXHAUSTED",
                "Notification delivery exhausted bounded retries", now);
        audits.insertOnce(message.tenantId(), auditId("FD-NOTIFICATION-DLQ-RECOVERY-AUDIT-V1",
                        message.tenantId(), message.messageId()),
                "NOTIFICATION_INBOX_RECOVERY_REQUIRED", "MESSAGE_RECOVERY", inboxId, "FAILED",
                "SERVICE", null, "Notification delivery exhausted bounded retries",
                "BROKER_DELIVERY_EXHAUSTED", message.correlationId(), message.causationId(), now);
        return NotificationConsumeResult.ack("NOTIFICATION_RECOVERY_REQUIRED");
    }

    /** 吸收重复死信前校验 Inbox 与 Recovery 一一对应。人工重放会修改状态和错误详情，因此这里只比较稳定身份及原消息事实。 */
    private void requireDlqRecovery(AcceptedNotification message,
                                    NotificationInboxRow inbox) {
        if (inbox == null || !"RECOVERY_REQUIRED".equals(inbox.status())
                || !message.tenantId().equals(NotificationSupport.context().tenantId())) {
            throw new IllegalStateException("Notification DLQ Inbox source is not recoverable");
        }
        UUID expectedRecoveryId = stableUuid(NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-RECOVERY-V1", message.tenantId(), inbox.inboxId()));
        NotificationRecoveryRow recovery = recoveries.findRecoveryBySource(message.tenantId(), inbox.inboxId(), true).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Notification DLQ Inbox has no paired Recovery"));
        if (!expectedRecoveryId.equals(recovery.recoveryId())
                || !"INBOX".equals(recovery.direction())
                || !inbox.inboxId().equals(recovery.sourceRecordId())
                || !Arrays.equals(message.businessIntentKey(), recovery.businessIntentKey())
                || recovery.recoveryRequiredAt() == null
                || !Set.of("OPEN", "REPLAYING", "RESOLVED").contains(recovery.state())
                || recovery.attemptCount() < NotificationEventContract.DELIVERY_ATTEMPTS) {
            throw new IllegalStateException("Notification DLQ Inbox/Recovery identity conflict");
        }
    }

    /** 通知副作用、Inbox 和成功审计同事务提交；消息去重与来源副作用去重共同避免重复通知。 */
    private NotificationConsumeResult process(AcceptedNotification message) {
        Instant now = now();
        NotificationInboxDuplicate duplicate = inboxes.findDuplicate(message, true);
        if (duplicate.kind() == NotificationInboxDuplicateKind.TERMINAL) {
            return NotificationConsumeResult.ack("NOTIFICATION_DUPLICATE");
        }
        if (duplicate.kind() == NotificationInboxDuplicateKind.CONFLICT) {
            audits.insertOnce(message.tenantId(), auditId("FD-NOTIFICATION-CONFLICT-AUDIT-V1",
                            message.tenantId(), message.messageId(), duplicate.conflictCode()),
                    "NOTIFICATION_INBOX_CONFLICT", "MESSAGE_RECOVERY", null, "REJECTED",
                    "SERVICE", null, "Message identity/content conflict", duplicate.conflictCode(),
                    message.correlationId(), message.causationId(), now);
            return NotificationConsumeResult.ack(duplicate.conflictCode());
        }
        // 通知 ID 由业务意图确定，来源副作用唯一键再检查内容一致性，重投消息不会生成第二条通知。
        UUID notificationId = stableUuid(message.businessIntentKey());
        try {
            repository.insertNotification(message.tenantId(), notificationId, message.recipientUserId(),
                    message.notificationKind(), message.binding().producerService(),
                    message.sourceEffectKey(), message.sourceRefType(), message.sourceRefId(),
                    message.title(), message.body(), now);
        }
        catch (DuplicateKeyException duplicateNotification) {
            NotificationRow existing = repository.findByEffect(
                    message.tenantId(), message.recipientUserId(), message.binding().producerService(),
                    message.sourceEffectKey(), message.notificationKind(), true).orElse(null);
            if (existing == null || !sameNotification(existing, message)) {
                audits.insertOnce(message.tenantId(), auditId("FD-NOTIFICATION-EFFECT-CONFLICT-AUDIT-V1",
                                message.tenantId(), message.businessIntentKey()),
                        "NOTIFICATION_EFFECT_CONFLICT", "NOTIFICATION", notificationId, "REJECTED",
                        "SERVICE", null, "Notification effect key/content conflict",
                        "NOTIFICATION_EFFECT_CONFLICT", message.correlationId(), message.causationId(), now);
                return NotificationConsumeResult.ack("NOTIFICATION_EFFECT_CONFLICT");
            }
            notificationId = existing.notificationId();
        }
        NotificationInboxDuplicate effectiveDuplicate = duplicate;
        if (effectiveDuplicate.kind() == NotificationInboxDuplicateKind.NONE) {
            UUID inboxId = stableUuid(NotificationWireCodec.deriveKey(
                    "FD-NOTIFICATION-INBOX-V1", message.tenantId(), message.messageId()));
            try {
                inboxes.insertProcessedInbox(message, inboxId, notificationId, now);
            }
            catch (DuplicateKeyException racedInsert) {
                effectiveDuplicate = inboxes.findDuplicate(message, true);
                if (effectiveDuplicate.kind() == NotificationInboxDuplicateKind.CONFLICT) {
                    audits.insertOnce(message.tenantId(), auditId("FD-NOTIFICATION-CONFLICT-AUDIT-V1",
                                    message.tenantId(), message.messageId(), effectiveDuplicate.conflictCode()),
                            "NOTIFICATION_INBOX_CONFLICT", "MESSAGE_RECOVERY", null, "REJECTED",
                            "SERVICE", null, "Message identity/content conflict",
                            effectiveDuplicate.conflictCode(), message.correlationId(),
                            message.causationId(), now);
                    return NotificationConsumeResult.ack(effectiveDuplicate.conflictCode());
                }
                if (effectiveDuplicate.kind() == NotificationInboxDuplicateKind.NONE) {
                    throw racedInsert;
                }
            }
        }
        if (effectiveDuplicate.kind() == NotificationInboxDuplicateKind.RECOVERABLE) {
            completeRecoveredInboxOrAccept(message, effectiveDuplicate.inbox(), notificationId, now);
        }
        audits.insertOnce(message.tenantId(), auditId("FD-NOTIFICATION-PROCESSED-AUDIT-V1",
                        message.tenantId(), message.businessIntentKey()),
                "NOTIFICATION_PROCESSED", "NOTIFICATION", notificationId, "SUCCEEDED",
                "SERVICE", null, "Notification consumed", null, message.correlationId(),
                message.causationId(), now);
        return NotificationConsumeResult.ack("NOTIFICATION_PROCESSED");
    }

    /** 仅在核实终态结果内容一致后，才将并发重放视作已完成。 */
    private void completeRecoveredInboxOrAccept(AcceptedNotification message,
                                                NotificationInboxRow row,
                                                UUID notificationId, Instant now) {
        int changed = inboxes.completeRecoveredInbox(message, row, notificationId, now);
        if (changed == 1) {
            resolveRecoveryStrict(message.tenantId(), recoveryIdFor(row), now);
            return;
        }
        NotificationInboxRow current = inboxes.findInbox(
                message.tenantId(), row.inboxId(), true).orElseThrow(
                () -> new IllegalStateException("Recovered Notification Inbox disappeared"));
        if (!"PROCESSED".equals(current.status())
                || !"NOTIFICATION".equals(current.resultType())
                || !notificationId.equals(current.resultId())) {
            throw new IllegalStateException("Recovered Notification Inbox race");
        }
        resolveRecoveryStrict(message.tenantId(), recoveryIdFor(current), now);
    }

    /** 对已经越过信任边界的载荷或收件人错误持久化确定性拒绝，不产生通知副作用。 */
    private NotificationConsumeResult rejectTrustedEnvelope(NotificationEnvelope envelope,
                                                             String code) {
        Instant now = now();
        NotificationInboxDuplicate duplicate = inboxes.findDuplicate(envelope, true);
        if (duplicate.kind() == NotificationInboxDuplicateKind.TERMINAL) {
            return NotificationConsumeResult.ack("NOTIFICATION_DUPLICATE");
        }
        if (duplicate.kind() == NotificationInboxDuplicateKind.CONFLICT) {
            audits.insertOnce(envelope.tenantId(),
                    auditId("FD-NOTIFICATION-CONFLICT-AUDIT-V1", envelope.tenantId(),
                            envelope.messageId(), code),
                    "NOTIFICATION_INBOX_CONFLICT", "INBOX_MESSAGE",
                    duplicate.inbox() == null ? null : duplicate.inbox().inboxId(), "REJECTED",
                    "SERVICE", null, "Message identity/content conflict",
                    safeCode(duplicate.conflictCode()), envelope.correlationId(),
                    envelope.causationId(), now);
            return NotificationConsumeResult.ack(duplicate.conflictCode());
        }

        UUID inboxId = stableUuid(NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-INBOX-V1", envelope.tenantId(), envelope.messageId()));
        if (duplicate.kind() == NotificationInboxDuplicateKind.RECOVERABLE) {
            NotificationInboxRow row = duplicate.inbox();
            if (inboxes.rejectRecoveredInbox(envelope, row, safeCode(code), now) != 1) {
                throw new IllegalStateException("Notification rejection recovery race");
            }
            recoveries.findRecoveryBySource(envelope.tenantId(), row.inboxId(), true).stream().findFirst()
                    .ifPresent(recovery -> resolveRecoveryStrict(
                            envelope.tenantId(), recovery.recoveryId(), now));
            inboxId = row.inboxId();
        }
        else {
            try {
                inboxes.insertRejectedInbox(envelope, inboxId, safeCode(code), now);
            }
            catch (DuplicateKeyException duplicateInsert) {
                NotificationInboxDuplicate raced = inboxes.findDuplicate(envelope, true);
                if (raced.kind() == NotificationInboxDuplicateKind.RECOVERABLE) {
                    if (inboxes.rejectRecoveredInbox(envelope, raced.inbox(), safeCode(code), now) != 1) {
                        throw new IllegalStateException("Notification rejection race");
                    }
                    inboxId = raced.inbox().inboxId();
                    recoveries.findRecoveryBySource(envelope.tenantId(), inboxId, true).stream().findFirst()
                            .ifPresent(recovery -> resolveRecoveryStrict(
                                    envelope.tenantId(), recovery.recoveryId(), now));
                }
                else if (raced.kind() == NotificationInboxDuplicateKind.TERMINAL) {
                    return NotificationConsumeResult.ack("NOTIFICATION_DUPLICATE");
                }
                else if (raced.kind() == NotificationInboxDuplicateKind.CONFLICT) {
                    return NotificationConsumeResult.ack(raced.conflictCode());
                }
                else {
                    throw duplicateInsert;
                }
            }
        }
        audits.insertOnce(envelope.tenantId(),
                auditId("FD-NOTIFICATION-REJECT-AUDIT-V1", envelope.tenantId(),
                        envelope.messageId(), safeCode(code)),
                "NOTIFICATION_REJECTED", "INBOX_MESSAGE", inboxId, "REJECTED",
                "SERVICE", null, "Notification payload rejected after envelope validation",
                safeCode(code), envelope.correlationId(), envelope.causationId(), now);
        return NotificationConsumeResult.ack(safeCode(code));
    }

    /**
     * 限制错误码格式和长度，避免异常文本污染恢复持久化字段。
     */
    private static String safeCode(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_:.]{0,95}")) {
            return "NOTIFICATION_PAYLOAD_REJECTED";
        }
        return code;
    }

    /**
     * 根据租户与原 Inbox 身份派生稳定恢复标识，重复死信不会创建第二条恢复生命周期。
     */
    private UUID recoveryIdFor(NotificationInboxRow inbox) {
        return recoveries.findRecoveryBySource(NotificationSupport.context().tenantId(), inbox.inboxId(), true).stream().findFirst()
                .map(NotificationRecoveryRow::recoveryId)
                .orElseThrow(() -> new IllegalStateException(
                        "Notification recovery source has no recovery row"));
    }

    /** 使用明确的一行 CAS 更新原消息关联的恢复记录。 */
    private void resolveRecoveryStrict(UUID tenantId, UUID recoveryId, Instant at) {
        NotificationRecoveryRow row = recoveries.findRecovery(tenantId, recoveryId, true).stream().findFirst().orElseThrow(() ->
                new IllegalStateException("Notification recovery row is missing"));
        if ("RESOLVED".equals(row.state())) return;
        int changed = recoveries.resolveRecovery(at, tenantId, recoveryId, row.lockVersion());
        if (changed != 1) {
            throw new IllegalStateException("Notification Recovery resolution CAS changed " + changed + " rows");
        }
    }

    /**
     * 核对通知接收人、来源副作用和内容，确认重复处理不会改变既有通知。
     */
    private static boolean sameNotification(NotificationRow row,
                                             AcceptedNotification message) {
        return Objects.equals(row.recipientUserId(), message.recipientUserId())
                && Objects.equals(row.notificationKind(), message.notificationKind())
                && Objects.equals(row.sourceService(), message.binding().producerService())
                && Arrays.equals(row.sourceEffectKey(), message.sourceEffectKey())
                && Objects.equals(row.sourceRefType(), message.sourceRefType())
                && Objects.equals(row.sourceRefId(), message.sourceRefId())
                && Objects.equals(row.title(), message.title())
                && Objects.equals(row.body(), message.body());
    }

    /**
     * 根据命名域和业务身份派生审计 UUID，使重复处理沿用同一审计锚点。
     */
    private static UUID auditId(String namespace, Object... fields) {
        return stableUuid(NotificationWireCodec.deriveKey(namespace, fields));
    }

    /**
     * 读取注入时钟并截断至数据库可保存的精度，便于持久化比较及测试。
     */
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    /**
     * 从稳定摘要生成 UUID，防止重复处理同一业务意图时创建新的记录身份。
     */
    private static UUID stableUuid(byte[] key) {
        byte[] first = Arrays.copyOf(key, 16);
        return ByteBufferHelper.uuid(first);
    }

    /**
     * 标识字节转换工具，保持摘要字节与 UUID 高低位编码一致。
     */
    private static final class ByteBufferHelper {
        /**
         * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
         */
        static UUID uuid(byte[] value) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(value);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
    }
}
