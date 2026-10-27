package com.flowdesk.ticket.incident.impl;

import com.flowdesk.ticket.incident.*;

import com.flowdesk.ticket.model.incident.*;
import com.flowdesk.ticket.persistence.Uuids;

import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.ticket.service.IdempotencyCodec;
import org.springframework.dao.DuplicateKeyException;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Incident 消息在工单侧的持久化边界。
 * 工单、映射与副作用标识均由不可变的租户、消息和业务意图派生，重复投递不会生成第二份业务事实。
 */
public final class IncidentInboxServiceImpl implements IncidentInboxService {
    private static final String PRODUCER = IncidentMessageContract.ALERT_PRODUCER;
    private final IncidentInboxPersistenceMapper mapper;

    /**
     * 构造告警建单组件并保存调用依赖。
     */
    public IncidentInboxServiceImpl(IncidentInboxPersistenceMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 按消息身份与业务意图读取既有 Inbox，区分首次处理、重放和冲突。
     */
    public Duplicate duplicate(AcceptedIncidentMessage message, boolean lock) {
        requireTenant(message.tenantId());
        Optional<Inbox> byMessage = mapper.duplicateSelect(bytes(message.tenantId()), PRODUCER, bytes(message.messageId()), lock)
                .stream().findFirst();
        if (byMessage.isPresent()) {
            return classify(message, byMessage.get(), "MESSAGE_ID_CONTENT_CONFLICT");
        }
        Optional<Inbox> byIntent = mapper.duplicateSelect2(bytes(message.tenantId()), PRODUCER, message.binding().contractRef(), message.businessIntentKey(), lock).stream().findFirst();
        return byIntent.map(row -> classify(message, row,
                        "BUSINESS_INTENT_CONTENT_CONFLICT"))
                .orElseGet(Duplicate::none);
    }

    /**
     * 比较持久化事实与当前消息，将重复投递分类为一致重放或冲突。
     */
    private static Duplicate classify(AcceptedIncidentMessage message, Inbox row,
                                      String conflictCode) {
        boolean same = message.messageId().equals(row.messageId())
                && message.binding().contractRef().equals(row.contractRef())
                && row.contractVersion() == IncidentMessageContract.VERSION
                && Arrays.equals(message.businessIntentKey(), row.intent())
                && Arrays.equals(message.bodySha256(), row.bodyHash())
                && Arrays.equals(message.bodyBytes(), row.body())
                && Objects.equals(message.correlationId(), row.correlationId())
                && Objects.equals(message.causationId(), row.causationId())
                && Objects.equals(message.envelopeOccurredAt(), row.occurredAt());
        if (!same) return new Duplicate(DuplicateKind.CONFLICT, row, conflictCode);
        return new Duplicate("RECOVERY_REQUIRED".equals(row.status())
                ? DuplicateKind.RECOVERABLE : DuplicateKind.TERMINAL, row, null);
    }

    /**
     * 读取租户告警建单策略，供创建工单与 SLA 时钟使用。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     */
    public Policy incidentPolicy(UUID tenantId) {
        requireTenant(tenantId);
        requireTransaction();
        List<Policy> rows = mapper.incidentPolicySelect(bytes(tenantId));
        return rows.size() == 1 ? rows.get(0) : null;
    }

    /**
     * 读取告警与工单的权威映射，防止同一告警重复建单。
     * 传入租户必须与当前上下文一致。
     */
    public Mapping mapping(UUID tenantId, UUID incidentId) {
        requireTenant(tenantId);
        return mapper.mappingSelect(bytes(tenantId), bytes(incidentId))
                .stream().findFirst().orElse(null);
    }

    /**
     * 在事务中分配当前租户业务日期下的下一个工单序号。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     */
    public long nextSequence(UUID tenantId, LocalDate businessDate, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        mapper.nextSequenceUpdate(bytes(tenantId), Date.valueOf(businessDate), Timestamp.from(now));
        Long value = mapper.nextSequenceSelect2(bytes(tenantId), Date.valueOf(businessDate));
        if (value == null || value < 1) throw new IllegalStateException("Ticket counter invalid");
        return value;
    }

    /**
     * 保存告警对应的工单事实，使用稳定身份支持重复投递去重。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     */
    public void insertTicket(AcceptedIncidentMessage message, UUID ignoredTicketId,
                             String ticketNo, Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        UUID ticketId = ticketId(message);
        String subject = "Monitoring incident " + message.incidentId();
        String description = "Monitoring fingerprint: " + message.fingerprint()
                + (message.summary() == null ? "" : "\nSummary: " + message.summary());
        try {
            mapper.insertTicketUpdate(bytes(message.tenantId()), bytes(ticketId), ticketNo, message.createTicketIntentKey(), subject, description, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        }
        catch (DuplicateKeyException duplicate) {
            TicketIdentity existing = mapper.insertTicketSelect2(bytes(message.tenantId()), bytes(ticketId), message.createTicketIntentKey()).stream().findFirst().orElseThrow(() -> duplicate);
            if (!ticketId.equals(existing.ticketId())
                    || !Arrays.equals(message.createTicketIntentKey(), existing.intent())
                    || !"INCIDENT".equals(existing.originType())
                    || !Objects.equals(ticketNo, existing.ticketNo())
                    || !Objects.equals(subject, existing.subject())
                    || !Objects.equals(description, existing.description())) {
                throw new IllegalStateException("Incident Ticket identity/content conflict", duplicate);
            }
        }
    }

    /**
     * 写入告警与工单的权威关联，保持后续恢复定位到同一工单。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 唯一约束竞争时回读既有事实并核验内容，避免重复产生业务效果。
     */
    public void insertMapping(AcceptedIncidentMessage message, UUID ignoredTicketId,
                              Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        UUID ticketId = ticketId(message);
        try {
            mapper.insertMappingUpdate(bytes(message.tenantId()), bytes(message.incidentId()), bytes(ticketId), message.createTicketIntentKey(), Timestamp.from(now), Timestamp.from(now));
        }
        catch (DuplicateKeyException duplicate) {
            Mapping existing = mapping(message.tenantId(), message.incidentId());
            if (existing == null || !ticketId.equals(existing.ticketId())
                    || !Arrays.equals(message.createTicketIntentKey(), existing.creationIntent())) {
                throw new IllegalStateException("Incident mapping identity/content conflict", duplicate);
            }
        }
    }

    /**
     * 记录工单业务时间线，使用稳定副作用键防止重复消费重复追加。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     */
    public void insertTimeline(AcceptedIncidentMessage message, UUID ticketId,
                               byte[] effectKey, String eventType, String summary,
                               Instant occurredAt) {
        requireTenant(message.tenantId());
        requireTransaction();
        byte[] effect = effectKey.clone();
        UUID timelineId = stableUuid("FD-INCIDENT-TIMELINE-V1", message.tenantId(),
                ticketId, effect);
        try {
            mapper.insertTimelineUpdate(bytes(message.tenantId()), bytes(timelineId), bytes(ticketId), effect, eventType, summary, bytes(message.incidentId()), Timestamp.from(occurredAt), bytes(message.correlationId()), bytes(message.messageId()), Timestamp.from(occurredAt));
        }
        catch (DuplicateKeyException duplicate) {
            TimelineIdentity existing = mapper.insertTimelineSelect2(bytes(message.tenantId()), bytes(ticketId), effect)
                    .stream().findFirst().orElseThrow(() -> duplicate);
            if (!timelineId.equals(existing.timelineId())
                    || !Objects.equals(eventType, existing.eventType())
                    || !Objects.equals(summary, existing.summary())
                    || !"INCIDENT".equals(existing.relatedRefType())
                    || !message.incidentId().equals(existing.relatedRefId())
                    || !"SERVICE".equals(existing.actorType()) || existing.actorId() != null
                    || !Objects.equals(occurredAt, existing.occurredAt())
                    || !message.correlationId().equals(existing.correlationId())
                    || !message.messageId().equals(existing.causationId())
                    || !Arrays.equals(effect, existing.effectKey())) {
                throw new IllegalStateException("Incident timeline identity/content conflict", duplicate);
            }
        }
    }

    /**
     * 按策略快照创建 SLA 时钟，使用来源身份派生稳定时钟标识。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 唯一约束竞争时回读既有事实并核验内容，避免重复产生业务效果。
     */
    public UUID insertClock(AcceptedIncidentMessage message, UUID ticketId, Policy policy,
                            String clockType, long duration, Long reminder, Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        Instant deadline = now.plusSeconds(duration);
        Instant reminderDue = reminder == null ? null : deadline.minusSeconds(reminder);
        UUID clockId = IncidentMessageIdentity.slaClockId(message.tenantId(), ticketId, clockType);
        try {
            mapper.insertClockUpdate(bytes(message.tenantId()), bytes(clockId), bytes(ticketId), bytes(policy.policyId()), clockType, Timestamp.from(now), Timestamp.from(deadline), timestamp(reminderDue), duration, reminder, Timestamp.from(now), Timestamp.from(now));
        }
        catch (DuplicateKeyException duplicate) {
            ClockIdentity existing = mapper.insertClockSelect2(bytes(message.tenantId()), bytes(ticketId), clockType)
                    .stream().findFirst().orElseThrow(() -> duplicate);
            if (!clockId.equals(existing.clockId()) || !policy.policyId().equals(existing.policyId())
                    || !"RUNNING".equals(existing.status()) || !now.equals(existing.startedAt())
                    || !deadline.equals(existing.deadlineAt())
                    || !Objects.equals(reminderDue, existing.reminderDueAt())
                    || duration != existing.duration()
                    || !Objects.equals(reminder, existing.reminder())) {
                throw new IllegalStateException("Incident SLA clock identity/content conflict", duplicate);
            }
        }
        return clockId;
    }

    /** 建单恢复与提交后索引投影共同使用的稳定时钟身份。 */


    public void insertOutboxEstablished(AcceptedIncidentMessage message, UUID ticketId,
                                        UUID ignoredOutboxId, UUID ignoredMessageId,
                                        byte[] ignoredPublicationKey, byte[] ignoredIntent,
                                        byte[] body, byte[] hash, Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        byte[] intent = IdempotencyCodec.derivedKey(
                IncidentMessageContract.ESTABLISHED_INTENT_NAMESPACE,
                message.tenantId(), message.incidentId());
        UUID messageId = stableUuid("FD-INCIDENT-ESTABLISHED-MESSAGE-V1",
                message.tenantId(), message.incidentId(), intent);
        byte[] publication = IdempotencyCodec.publicationKey(
                "FD-INCIDENT-ESTABLISHED-PUBLICATION-V1", message.tenantId(), messageId);
        UUID outboxId = stableUuid("FD-INCIDENT-ESTABLISHED-OUTBOX-V1",
                message.tenantId(), message.incidentId(), intent);
        try {
            mapper.insertOutboxEstablishedUpdate(bytes(message.tenantId()), bytes(outboxId), bytes(messageId), publication, intent, bytes(message.incidentId()), IncidentMessageContract.ESTABLISHED_CONTRACT_REF, body, hash, bytes(message.correlationId()), bytes(message.messageId()), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        }
        catch (DuplicateKeyException duplicate) {
            OutboxIdentity existing = mapper.insertOutboxEstablishedSelect2(bytes(message.tenantId()), publication)
                    .stream().findFirst().orElseThrow(() -> duplicate);
            if (!outboxId.equals(existing.outboxId()) || !messageId.equals(existing.messageId())
                    || !Arrays.equals(publication, existing.publicationKey())
                    || !Arrays.equals(intent, existing.intent())
                    || !message.incidentId().equals(existing.aggregateId())
                    || !IncidentMessageContract.ESTABLISHED_CONTRACT_REF.equals(existing.contractRef())
                    || existing.contractVersion() != IncidentMessageContract.VERSION
                    || !Arrays.equals(body, existing.body()) || !Arrays.equals(hash, existing.hash())
                    || !message.correlationId().equals(existing.correlationId())
                    || !message.messageId().equals(existing.causationId())) {
                throw new IllegalStateException("Incident established Outbox identity/content conflict", duplicate);
            }
        }
    }

    /**
     * 保存业务操作审计，记录目标、主体、结果和请求链路。
     */
    public void insertAudit(AcceptedIncidentMessage message, UUID targetId, String action,
                            String outcome, String reason, String error, Instant now) {
        insertAudit(message, stableUuid("FD-INCIDENT-AUDIT-V1", message.tenantId(),
                        message.messageId(), targetId == null ? ZERO : targetId,
                        action, outcome, reason == null ? "" : reason,
                        error == null ? "" : error),
                targetId, action, outcome, reason, error, now);
    }

    /**
     * 保存业务操作审计，记录目标、主体、结果和请求链路。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 唯一约束竞争时回读既有事实并核验内容，避免重复产生业务效果。
     */
    public void insertAudit(AcceptedIncidentMessage message, UUID auditId, UUID targetId,
                            String action, String outcome, String reason, String error,
                            Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        try {
            mapper.insertAuditUpdate(bytes(message.tenantId()), bytes(auditId), action, bytes(targetId), outcome, reason, error, bytes(message.correlationId()), bytes(message.messageId()), Timestamp.from(now), Timestamp.from(now));
        }
        catch (DuplicateKeyException duplicate) {
            AuditIdentity existing = mapper.insertAuditSelect2(bytes(message.tenantId()), bytes(auditId))
                    .stream().findFirst().orElseThrow(() -> duplicate);
            if (!Objects.equals(action, existing.action()) || !"TICKET".equals(existing.targetType())
                    || !Objects.equals(targetId, existing.targetId()) || !Objects.equals(outcome, existing.outcome())
                    || !"SERVICE".equals(existing.actorType()) || existing.actorId() != null
                    || !Objects.equals(reason, existing.reason()) || !Objects.equals(error, existing.error())
                    || !message.correlationId().equals(existing.correlationId())
                    || !message.messageId().equals(existing.causationId())) {
                throw new IllegalStateException("Incident audit identity/content conflict", duplicate);
            }
        }
    }

    /**
     * 保存成功处理的 Inbox 事实及结果关联，使重复投递直接收敛。
     */
    public void insertProcessedInbox(AcceptedIncidentMessage message, UUID ignoredInbox,
                                     UUID result, Instant now) {
        insertInbox(message, "PROCESSED", "TICKET", result, 1, null, now, now);
    }

    /**
     * 保存拒绝消息结果与错误码，避免毒消息反复执行业务。
     */
    public void insertRejectedInbox(AcceptedIncidentMessage message, UUID ignoredInbox,
                                    String code, Instant now) {
        insertInbox(message, "REJECTED", null, null, 1, code, now, now);
    }

    /**
     * 保存待恢复 Inbox，保留原始消息供受控重放。
     */
    public void insertRecoveryInbox(AcceptedIncidentMessage message, UUID ignoredInbox,
                                    String code, Instant now) {
        insertInbox(message, "RECOVERY_REQUIRED", null, null,
                IncidentMessageContract.DELIVERY_ATTEMPTS, code, now, null);
    }

    /**
     * 保存消息接收身份与状态，为重复投递及恢复提供一致锚点。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     */
    private void insertInbox(AcceptedIncidentMessage message, String status, String resultType,
                             UUID result, int attempts, String error, Instant now,
                             Instant processedAt) {
        requireTenant(message.tenantId());
        requireTransaction();
        UUID inboxId = inboxId(message);
        try {
            mapper.insertInboxUpdate(bytes(message.tenantId()), bytes(inboxId), PRODUCER, bytes(message.messageId()), message.businessIntentKey(), message.binding().contractRef(), message.bodySha256(), message.bodyBytes(), bytes(message.correlationId()), bytes(message.causationId()), Timestamp.from(message.envelopeOccurredAt()), status, resultType, bytes(result), attempts, error, Timestamp.from(now), Timestamp.from(now), timestamp(processedAt), Timestamp.from(now), Timestamp.from(now));
        }
        catch (DuplicateKeyException duplicate) {
            Inbox existing = mapper.insertInboxSelect2(bytes(message.tenantId()), bytes(inboxId))
                    .stream().findFirst().orElseThrow(() -> duplicate);
            if (!sameSource(message, existing)) {
                throw new IllegalStateException("Incident Inbox identity/content conflict", duplicate);
            }
        }
    }

    /**
     * 恢复成功后条件更新 Inbox 为已处理，并关联最终结果。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     */
    public void completeRecoveredInbox(AcceptedIncidentMessage message, Inbox inbox,
                                       UUID result, Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        int changed = mapper.completeRecoveredInboxUpdate(bytes(result), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), bytes(message.tenantId()), bytes(inbox.inboxId()), inbox.lockVersion());
        if (changed == 1) {
            resolveRecovery(message.tenantId(), inbox.inboxId(), now);
            return;
        }
        Inbox current = findInbox(message.tenantId(), inbox.inboxId(), true)
                .orElseThrow(() -> new IllegalStateException("Incident Inbox disappeared"));
        if (!"PROCESSED".equals(current.status()) || !"TICKET".equals(current.resultType())
                || !Objects.equals(result, current.resultId()) || !sameSource(message, current)) {
            throw new IllegalStateException("Incident Inbox completion CAS/content conflict");
        }
        resolveRecovery(message.tenantId(), inbox.inboxId(), now);
    }

    /**
     * 将恢复中的 Inbox 收敛为已拒绝，保留不可继续处理的原因。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     */
    public void rejectRecoveredInbox(AcceptedIncidentMessage message, Inbox inbox,
                                     String code, Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        int changed = mapper.rejectRecoveredInboxUpdate(code, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), bytes(message.tenantId()), bytes(inbox.inboxId()), inbox.lockVersion());
        if (changed == 1) {
            resolveRecovery(message.tenantId(), inbox.inboxId(), now);
            return;
        }
        Inbox current = findInbox(message.tenantId(), inbox.inboxId(), true)
                .orElseThrow(() -> new IllegalStateException("Incident Inbox disappeared"));
        if (!"REJECTED".equals(current.status()) || current.resultType() != null
                || current.resultId() != null || !Objects.equals(code, current.lastErrorCode())
                || !sameSource(message, current)) {
            throw new IllegalStateException("Incident Inbox rejection CAS/content conflict");
        }
        resolveRecovery(message.tenantId(), inbox.inboxId(), now);
    }

    /**
     * 保存消息恢复记录和来源关联，供查询与人工重放。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 唯一约束竞争时回读既有事实并核验内容，避免重复产生业务效果。
     */
    public void insertRecovery(AcceptedIncidentMessage message, UUID ignoredInbox,
                               UUID ignoredRecovery, String code, Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        UUID inboxId = inboxId(message);
        UUID recoveryId = stableUuid("FD-INCIDENT-RECOVERY-V1", message.tenantId(), inboxId);
        String summary = "Incident message exhausted bounded Ticket retries.";
        try {
            mapper.insertRecoveryUpdate(bytes(message.tenantId()), bytes(recoveryId), bytes(inboxId), message.businessIntentKey(), IncidentMessageContract.DELIVERY_ATTEMPTS, code, summary, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        }
        catch (DuplicateKeyException duplicate) {
            RecoveryIdentity existing = mapper.insertRecoverySelect2(bytes(message.tenantId()), bytes(inboxId))
                    .stream().findFirst().orElseThrow(() -> duplicate);
            if (!recoveryId.equals(existing.recoveryId())
                    || !Arrays.equals(message.businessIntentKey(), existing.intent())
                    || !"OPEN".equals(existing.state())
                    || existing.attemptCount() != IncidentMessageContract.DELIVERY_ATTEMPTS
                    || !Objects.equals(code, existing.errorCode())
                    || !Objects.equals(summary, existing.summary())) {
                throw new IllegalStateException("Incident Recovery identity/content conflict", duplicate);
            }
        }
    }

    /**
     * 完成已满足收敛条件的恢复项，避免再次重放终态事实。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     */
    private void resolveRecovery(UUID tenantId, UUID inboxId, Instant now) {
        requireTransaction();
        Optional<RecoveryState> state = mapper.resolveRecoverySelect(bytes(tenantId), bytes(inboxId)).stream().findFirst();
        if (state.isEmpty() || "RESOLVED".equals(state.get().state())) return;
        if (!SetOf.OPEN_REPLAYING.contains(state.get().state())) {
            throw new IllegalStateException("Incident Recovery state conflict");
        }
        int changed = mapper.resolveRecoveryUpdate2(Timestamp.from(now), Timestamp.from(now), bytes(tenantId), bytes(state.get().recoveryId()), state.get().state(), state.get().lockVersion());
        if (changed != 1) throw new IllegalStateException("Incident Recovery resolution CAS lost");
    }

    /**
     * 按租户与消息身份读取 Inbox，供重复消费或恢复结算核验。
     */
    private Optional<Inbox> findInbox(UUID tenantId, UUID inboxId, boolean lock) {
        requireTenant(tenantId);
        return mapper.findInboxSelect(bytes(tenantId), bytes(inboxId), lock).stream().findFirst();
    }

    /**
     * 比较来源身份、原文和摘要，防止不同消息共用一条幂等记录。
     */
    private static boolean sameSource(AcceptedIncidentMessage message, Inbox row) {
        return message.messageId().equals(row.messageId())
                && message.binding().contractRef().equals(row.contractRef())
                && row.contractVersion() == IncidentMessageContract.VERSION
                && Arrays.equals(message.businessIntentKey(), row.intent())
                && Arrays.equals(message.bodyBytes(), row.body())
                && Arrays.equals(message.bodySha256(), row.bodyHash())
                && Objects.equals(message.correlationId(), row.correlationId())
                && Objects.equals(message.causationId(), row.causationId())
                && Objects.equals(message.envelopeOccurredAt(), row.occurredAt());
    }

    /**
     * 返回关联工单标识，供消息处理定位业务事实。
     */
    private static UUID ticketId(AcceptedIncidentMessage message) {
        return stableUuid("FD-INCIDENT-TICKET-V1", message.tenantId(),
                message.incidentId(), message.createTicketIntentKey());
    }

    /**
     * 从消息稳定身份派生 Inbox 标识，使重复投递命中同一接收记录。
     */
    private static UUID inboxId(AcceptedIncidentMessage message) {
        return stableUuid("FD-INCIDENT-INBOX-V1", message.tenantId(), message.messageId());
    }

    /**
     * 从规范业务键派生稳定 UUID，使重试与恢复复用同一业务身份。
     */
    private static UUID stableUuid(String namespace, Object... fields) {
        return IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(namespace, fields));
    }

    private static final UUID ZERO = new UUID(0L, 0L);

    /**
     * 转换业务标识与规范 UUID 字节表示，遵循既有空值与格式校验约定。
     */
    private static byte[] bytes(UUID value) { return Uuids.toBytes(value); }
    /**
     * 解析时间文本或转换数据库时间值，遵循空值约定并保留业务精度。
     */
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    /**
     * 校验传入租户与当前上下文一致，阻止跨租户持久化操作。
     */
    private static void requireTenant(UUID tenantId) { TenantContextHolder.current().requireTenant(tenantId); }
    /**
     * 要求调用处于数据库事务中，保证关联事实可以原子提交或回滚。
     */
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Incident mutation requires a transaction");
        }
    }













    /**
     * 告警建单重复校验的内部辅助类型，集中比较相关事实身份。
     */
    private static final class SetOf {
        private static final java.util.Set<String> OPEN_REPLAYING = java.util.Set.of("OPEN", "REPLAYING");
    }
}
