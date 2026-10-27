package com.flowdesk.notification.service.impl;
import com.flowdesk.notification.service.NotificationInboxService;
import com.flowdesk.notification.event.*;
import com.flowdesk.notification.model.*;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.notification.NotificationWireCodec;
import com.flowdesk.notification.event.AcceptedNotification;
import com.flowdesk.notification.event.NotificationContract;
import com.flowdesk.notification.event.NotificationEnvelope;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import com.flowdesk.notification.mapper.NotificationInboxMapper;


import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.flowdesk.notification.service.NotificationPersistenceSupport.*;

/** 运行账号下的 Inbox 持久化边界，查询只读元数据，按完整信封裁决重复和冲突。 */
@org.springframework.stereotype.Service
public class NotificationInboxServiceImpl implements NotificationInboxService {
    private final NotificationInboxMapper mapper;

    /**
     * 注入 Inbox 映射，供消息身份、意图和发布键去重及恢复状态变更共用同一持久化入口。
     */
    public NotificationInboxServiceImpl(NotificationInboxMapper mapper) {
        this.mapper = mapper;
    }

    // Inbox 的去重、状态写入与元数据读取。

    /** 依次按消息、业务意图和发布标识判重，任何身份命中后都核对完整不可变信封。 */
    public NotificationInboxDuplicate findDuplicate(AcceptedNotification message, boolean lock) {
        return findDuplicate(message.envelope(), lock);
    }

    /** 仅依据完整不可变传输信封查找重复消息。 */
    public NotificationInboxDuplicate findDuplicate(NotificationEnvelope message, boolean lock) {
        requireTenant(message.tenantId());
        Optional<NotificationInboxRow> byMessage = mapper.findByMessage(message.tenantId(), message.binding().producerService(), message.messageId(), lock).stream().findFirst();
        if (byMessage.isPresent()) return classify(message, byMessage.get(), "MESSAGE_ID_CONTENT_CONFLICT");
        Optional<NotificationInboxRow> byIntent = mapper.findByIntent(message.tenantId(), message.binding().producerService(), message.binding().contractRef(), message.businessIntentKey(), lock).stream().findFirst();
        if (byIntent.isPresent()) {
            return classify(message, byIntent.get(), "BUSINESS_INTENT_CONTENT_CONFLICT");
        }
        Optional<NotificationInboxRow> byPublication = findInboxByPublication(
                message.tenantId(), message.publicationKey(), lock);
        return byPublication.map(row -> classify(message, row,
                        "PUBLICATION_CONTENT_CONFLICT"))
                .orElseGet(NotificationInboxDuplicate::none);
    }

    /**
     * 比对既有 Inbox 身份、契约和摘要，将输入区分为首次、终态重复、待恢复重复或冲突。
     */
    private static NotificationInboxDuplicate classify(NotificationEnvelope message, NotificationInboxRow row, String code) {
        boolean same = Objects.equals(message.binding().producerService(), row.producerService())
                && Objects.equals(message.messageId(), row.messageId())
                && Objects.equals(message.messageKey(), row.messageKey())
                && Objects.equals(message.binding().topic(), row.topic())
                && Objects.equals(message.binding().consumerGroup(), row.consumerGroup())
                && Objects.equals(message.logicalDestination(), row.logicalDestination())
                && "INBOX".equals(row.direction())
                && Objects.equals(message.binding().contractRef(), row.contractRef())
                && row.contractVersion() == NotificationEventContract.VERSION
                && Objects.equals(message.aggregateType(), row.aggregateType())
                && Objects.equals(message.aggregateId(), row.aggregateId())
                && Arrays.equals(message.businessIntentKey(), row.businessIntentKey())
                && Arrays.equals(message.publicationKey(), row.publicationKey())
                && Arrays.equals(message.bodySha256(), row.bodySha256())
                // 普通运行账号将 body_bytes 投影为 NULL；本次信封的 SHA-256 已在查询前验证，因此在最小权限路径下使用摘要比对原消息。
                && (row.bodyBytes() == null || Arrays.equals(message.bodyBytes(), row.bodyBytes()))
                && Objects.equals(message.correlationId(), row.correlationId())
                && Objects.equals(message.causationId(), row.causationId())
                && Objects.equals(message.occurredAt(), row.occurredAt());
        if (!same) return new NotificationInboxDuplicate(NotificationInboxDuplicateKind.CONFLICT, row, code);
        if ("RECOVERY_REQUIRED".equals(row.status())) {
            return new NotificationInboxDuplicate(NotificationInboxDuplicateKind.RECOVERABLE, row, null);
        }
        return new NotificationInboxDuplicate(NotificationInboxDuplicateKind.TERMINAL, row, null);
    }

    /**
     * 持久化处理成功 Inbox，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    public void insertProcessedInbox(AcceptedNotification message, UUID inboxId,
                                     UUID resultId, Instant now) {
        insertInbox(message.envelope(), inboxId, "PROCESSED", "NOTIFICATION", resultId,
                1, null, now, now);
    }

    /**
     * 持久化确定性拒绝 Inbox，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    public void insertRejectedInbox(AcceptedNotification message, UUID inboxId,
                                     String code, Instant now) {
        insertRejectedInbox(message.envelope(), inboxId, code, now);
    }

    /** 信封越过信任边界后，将确定性拒绝持久化。 */
    public void insertRejectedInbox(NotificationEnvelope message, UUID inboxId,
                                    String code, Instant now) {
        insertInbox(message, inboxId, "REJECTED", null, null, 1, code, now, now);
    }

    /**
     * 持久化待恢复 Inbox，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    public void insertRecoveryInbox(AcceptedNotification message, UUID inboxId,
                                     String code, Instant now) {
        insertInbox(message.envelope(), inboxId, "RECOVERY_REQUIRED", null, null,
                NotificationContract.DELIVERY_ATTEMPTS, code, now, null);
    }

    /** 持久化 ID 必须由租户与消息 ID 确定，唯一键竞争时仅吸收内容和结果均一致的记录。 */
    private void insertInbox(NotificationEnvelope message, UUID inboxId, String status,
                              String resultType, UUID resultId, int attempts, String error,
                              Instant now, Instant processedAt) {
        requireTenant(message.tenantId());
        requireTransaction();
        UUID expectedInboxId = uuidFromKey(NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-INBOX-V1", message.tenantId(), message.messageId()));
        if (!expectedInboxId.equals(inboxId)) {
            throw new IllegalStateException("Notification Inbox identity is not deterministic");
        }
        try {
            mapper.insertInbox(message.tenantId(), inboxId, message.binding().producerService(), message.messageId(), message.messageKey(), message.businessIntentKey(), message.publicationKey(), message.binding().contractRef(), NotificationContract.VERSION, message.aggregateType(), message.aggregateId(), message.bodySha256(), message.bodyBytes(), message.topic(), message.consumerGroup(), message.logicalDestination(), message.correlationId(), message.causationId(), message.occurredAt(), status, resultType, resultId, attempts, error, now, processedAt);
        }
        catch (DuplicateKeyException duplicate) {
            NotificationInboxRow existing = findInbox(message.tenantId(), expectedInboxId, true)
                    .orElseThrow(() -> duplicate);
            if (!sameSource(message, existing)
                    || !Objects.equals(existing.status(), status)
                    || !Objects.equals(existing.resultType(), resultType)
                    || !Objects.equals(existing.resultId(), resultId)
                    || !Objects.equals(existing.lastErrorCode(), error)) {
                throw new IllegalStateException("Notification Inbox identity/content conflict", duplicate);
            }
        }
    }

    /**
     * 核对来源消息的稳定身份与内容，防止重复索引命中后覆盖不同事实。
     */
    private static boolean sameSource(NotificationEnvelope message, NotificationInboxRow row) {
        return Objects.equals(message.binding().producerService(), row.producerService())
                && Objects.equals(message.messageId(), row.messageId())
                && Objects.equals(message.messageKey(), row.messageKey())
                && Objects.equals(message.binding().topic(), row.topic())
                && Objects.equals(message.binding().consumerGroup(), row.consumerGroup())
                && Objects.equals(message.logicalDestination(), row.logicalDestination())
                && "INBOX".equals(row.direction())
                && Objects.equals(message.binding().contractRef(), row.contractRef())
                && row.contractVersion() == NotificationEventContract.VERSION
                && Objects.equals(message.aggregateType(), row.aggregateType())
                && Objects.equals(message.aggregateId(), row.aggregateId())
                && Arrays.equals(message.businessIntentKey(), row.businessIntentKey())
                && Arrays.equals(message.publicationKey(), row.publicationKey())
                && Arrays.equals(message.bodySha256(), row.bodySha256())
                && (row.bodyBytes() == null || Arrays.equals(message.bodyBytes(), row.bodyBytes()))
                && Objects.equals(message.correlationId(), row.correlationId())
                && Objects.equals(message.causationId(), row.causationId())
                && Objects.equals(message.occurredAt(), row.occurredAt());
    }

    /**
     * 按原 Inbox 身份结算恢复结果，避免重新创建重复接纳事实。
     */
    public int completeRecoveredInbox(AcceptedNotification message, NotificationInboxRow row,
                                      UUID resultId, Instant now) {
        return completeRecoveredInbox(message.envelope(), row, resultId, now);
    }

    /**
     * 按原 Inbox 身份结算恢复结果，避免重新创建重复接纳事实。
     */
    public int completeRecoveredInbox(NotificationEnvelope message, NotificationInboxRow row,
                                      UUID resultId, Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        return mapper.completeRecoveredInbox(resultId, now, message.tenantId(), row.inboxId(), row.lockVersion());
    }

    /**
     * 将待恢复 Inbox 更新为拒绝结果，保留原消息身份和内容。
     */
    public int rejectRecoveredInbox(AcceptedNotification message, NotificationInboxRow row,
                                    String code, Instant now) {
        return rejectRecoveredInbox(message.envelope(), row, code, now);
    }

    /**
     * 将待恢复 Inbox 更新为拒绝结果，保留原消息身份和内容。
     */
    public int rejectRecoveredInbox(NotificationEnvelope message, NotificationInboxRow row,
                                    String code, Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        return mapper.rejectRecoveredInbox(code, now, message.tenantId(), row.inboxId(), row.lockVersion());
    }

    /**
     * 按声明的租户与业务条件查询Inbox，供调用方核对可见性、状态或幂等关系。
     */
    public Optional<NotificationInboxRow> findInbox(UUID tenantId, UUID inboxId, boolean lock) {
        requireTenant(tenantId);
        return mapper.findById(tenantId, inboxId, lock).stream().findFirst();
    }

    /** 按精确发布标识查找 Inbox，检测重复的 Outbox 副作用。 */
    public Optional<NotificationInboxRow> findInboxByPublication(UUID tenantId, byte[] publicationKey,
                                                     boolean lock) {
        requireTenant(tenantId);
        return mapper.findByPublication(tenantId, publicationKey, lock).stream().findFirst();
    }






}
