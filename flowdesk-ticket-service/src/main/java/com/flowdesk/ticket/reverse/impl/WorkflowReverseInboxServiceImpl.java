package com.flowdesk.ticket.reverse.impl;

import com.flowdesk.ticket.reverse.*;

import com.flowdesk.ticket.model.reverse.*;
import com.flowdesk.ticket.persistence.Uuids;

import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.ticket.service.IdempotencyCodec;
import org.springframework.dao.DuplicateKeyException;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 流程反向事件的租户隔离持久化边界，所有读写均要求匹配的租户上下文和活动事务。 */
public final class WorkflowReverseInboxServiceImpl implements WorkflowReverseInboxService {
    private final WorkflowReverseInboxPersistenceMapper mapper;

    /**
     * 构造工作流反向结果组件并保存调用依赖。
     */
    public WorkflowReverseInboxServiceImpl(WorkflowReverseInboxPersistenceMapper mapper) { this.mapper = mapper; }

    /**
     * 读取既有接收事实，供消费前判断重复投递及内容冲突。
     */
    public Duplicate findDuplicate(AcceptedWorkflowReverse message, boolean lock) {
        requireTenant(message.tenantId());
        Optional<Inbox> byMessage = mapper.findDuplicateSelect(Uuids.toBytes(message.tenantId()), WorkflowReverseContract.PRODUCER, Uuids.toBytes(message.messageId()), lock)
                .stream().findFirst();
        if (byMessage.isPresent()) return classify(message, byMessage.get(), "MESSAGE_ID_CONTENT_CONFLICT");
        Optional<Inbox> byIntent = mapper.findDuplicateSelect2(Uuids.toBytes(message.tenantId()), WorkflowReverseContract.PRODUCER, message.binding().contractRef(), message.businessIntentKey(), lock).stream().findFirst();
        return byIntent.map(inbox -> classify(message, inbox, "BUSINESS_INTENT_CONTENT_CONFLICT"))
                .orElseGet(Duplicate::none);
    }

    /**
     * 比较持久化事实与当前消息，将重复投递分类为一致重放或冲突。
     */
    private static Duplicate classify(AcceptedWorkflowReverse message, Inbox inbox, String conflict) {
        boolean same = message.binding().contractRef().equals(inbox.contractRef())
                && message.messageId().equals(inbox.messageId())
                && inbox.contractVersion() == WorkflowReverseContract.VERSION
                && Arrays.equals(message.businessIntentKey(), inbox.intent())
                && Arrays.equals(message.bodySha256(), inbox.bodyHash())
                && Arrays.equals(message.bodyBytes(), inbox.body())
                && Objects.equals(message.correlationId(), inbox.correlationId())
                && Objects.equals(message.causationId(), inbox.causationId())
                && Objects.equals(message.envelopeOccurredAt(), inbox.occurredAt());
        if (!same) return new Duplicate(DuplicateKind.CONFLICT, inbox, conflict);
        if ("RECOVERY_REQUIRED".equals(inbox.status())) {
            return new Duplicate(DuplicateKind.RECOVERABLE, inbox, null);
        }
        return new Duplicate(DuplicateKind.TERMINAL, inbox, null);
    }

    /**
     * 读取工单、启动关联及相关事实，供反向消息在事务中统一校验。
     */
    public Aggregate findAggregate(AcceptedWorkflowReverse message) {
        requireTenant(message.tenantId());
        return mapper.findAggregateSelect(Uuids.toBytes(message.tenantId()), Uuids.toBytes(message.ticketId())).stream().findFirst().orElse(null);
    }

    /**
     * 建立工单与工作流实例关联，已有绑定必须与原启动意图一致。
     */
    public void establishLink(AcceptedWorkflowReverse message, Aggregate aggregate, Instant now) {
        if (!"ESTABLISHED".equals(aggregate.linkStatus())) {
            int updated = mapper.establishLinkUpdate(Uuids.toBytes(message.workflowInstanceId()), Timestamp.from(now), Timestamp.from(now), Uuids.toBytes(message.tenantId()), Uuids.toBytes(message.ticketId()), aggregate.linkLockVersion());
            requireOne(updated, "WorkflowStartLink establish");
        }
        resolveStartPublicationRecovery(message, now);
    }

    /**
     * 实例建立后收敛启动发布恢复，避免重发已产生业务效果的请求。
     */
    private void resolveStartPublicationRecovery(AcceptedWorkflowReverse message, Instant now) {
        List<RecoveryState> recoveries = mapper.resolveStartPublicationRecoverySelect(Uuids.toBytes(message.tenantId()), Uuids.toBytes(message.ticketId()));
        for (RecoveryState recovery : recoveries) {
            if ("RESOLVED".equals(recovery.state())) {
                continue;
            }
            if (!Set.of("OPEN", "REPLAYING").contains(recovery.state())) {
                throw new IllegalStateException("Workflow start recovery state conflict");
            }
            int changed = mapper.resolveStartPublicationRecoveryUpdate2(Timestamp.from(now), Timestamp.from(now), Uuids.toBytes(message.tenantId()), Uuids.toBytes(recovery.recoveryId()), recovery.state(), recovery.lockVersion());
            requireOne(changed, "Workflow start recovery resolution");
        }
    }

    /**
     * 按工作流终态条件推进工单，防止相反审批结论覆盖既定结果。
     */
    public FinalizationResult finalizeTicket(AcceptedWorkflowReverse message,
                                             Aggregate aggregate, Instant now) {
        String target = "PASSED".equals(message.outcome())
                ? "PENDING_ASSIGNMENT" : "APPROVAL_REJECTED";
        int updated;
        if ("PASSED".equals(message.outcome())) {
            updated = mapper.finalizeTicketUpdate(Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Uuids.toBytes(message.tenantId()), Uuids.toBytes(message.ticketId()), aggregate.ticketLockVersion());
        }
        else {
            updated = mapper.finalizeTicketUpdate2(Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Uuids.toBytes(message.tenantId()), Uuids.toBytes(message.ticketId()), aggregate.ticketLockVersion());
        }
        if (updated == 1) return FinalizationResult.APPLIED;
        // 人工恢复时工单可能已沿审批通过后的状态继续推进到分配、处理中、解决甚至关闭。
        // 这些状态是同一终结事实的合法后继，需补齐副作用而不能再次改变工单状态。
        if (("PASSED".equals(message.outcome()) && Set.of("PENDING_ASSIGNMENT",
                "ASSIGNED", "IN_PROGRESS", "RESOLVED", "CLOSED")
                .contains(aggregate.ticketStatus()))
                || ("REJECTED".equals(message.outcome()) && "APPROVAL_REJECTED".equals(aggregate.ticketStatus()))) {
            return FinalizationResult.ALREADY_APPLIED;
        }
        requireOne(updated, "Ticket approval finalization to " + target);
        return FinalizationResult.APPLIED;
    }

    /**
     * 从进入可处理状态的时点创建 SLA 时钟，重复消费复用稳定来源身份。
     */
    public void insertSlaClocks(AcceptedWorkflowReverse message, Aggregate aggregate,
                                Instant startedAt, Instant recordedAt) {
        if (startedAt == null || recordedAt == null) {
            throw new IllegalArgumentException("Workflow reverse SLA start/recorded time is required");
        }
        Instant start = startedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant recorded = recordedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        insertClock(message, aggregate, "FIRST_RESPONSE", aggregate.firstResponseSeconds(),
                aggregate.firstResponseReminderSeconds(), start, recorded);
        insertClock(message, aggregate, "RESOLUTION", aggregate.resolutionSeconds(),
                aggregate.resolutionReminderSeconds(), start, recorded);
    }

    /**
     * 兼容已持有工单持久化起算事实的调用方。
     * Workflow 的 occurredAt 仅用于因果追踪，绝不用于重建 SLA 截止时间。
     */
    public void insertSlaClocks(AcceptedWorkflowReverse message, Aggregate aggregate,
                                Instant recordedAt) {
        Instant start = aggregate.firstProcessableAt();
        if (start == null) {
            throw new IllegalStateException("Ticket finalization has no persisted SLA start time");
        }
        insertSlaClocks(message, aggregate, start, recordedAt);
    }

    /**
     * 按策略快照创建 SLA 时钟，使用来源身份派生稳定时钟标识。
     * 唯一约束竞争时回读既有事实并核验内容，避免重复产生业务效果。
     */
    private void insertClock(AcceptedWorkflowReverse message, Aggregate aggregate,
                              String type, Long duration, Long reminder,
                              Instant started, Instant recordedAt) {
        if (started == null || recordedAt == null || duration == null || duration <= 0) {
            throw new IllegalArgumentException("Workflow reverse SLA clock timestamps are incomplete");
        }
        UUID clockId = WorkflowReverseIdentity.slaClockId(message.tenantId(), message.ticketId(), type);
        Instant deadline = started.plusSeconds(duration);
        Instant reminderAt = reminder == null ? null : deadline.minusSeconds(reminder);
        ClockIdentity existing = mapper.insertClockSelect(Uuids.toBytes(message.tenantId()), Uuids.toBytes(message.ticketId()), type).stream().findFirst().orElse(null);
        if (existing != null) {
            if (!clockId.equals(existing.clockId())
                    || !aggregate.slaPolicyId().equals(existing.slaPolicyId())
                    || !started.equals(existing.startedAt())
                    || !deadline.equals(existing.deadlineAt())
                    || !Objects.equals(reminderAt, existing.reminderDueAt())
                    || duration.longValue() != existing.duration()
                    || !Objects.equals(reminder, existing.reminder())) {
                throw new IllegalStateException("Workflow reverse SLA clock identity/content conflict");
            }
            return;
        }
        try {
            mapper.insertClockUpdate2(Uuids.toBytes(message.tenantId()), Uuids.toBytes(clockId), Uuids.toBytes(message.ticketId()), Uuids.toBytes(aggregate.slaPolicyId()), type, Timestamp.from(started), Timestamp.from(deadline), timestamp(reminderAt), duration, reminder, Timestamp.from(recordedAt), Timestamp.from(recordedAt));
        }
        catch (DuplicateKeyException duplicate) {
            ClockIdentity raced = mapper.insertClockSelect3(Uuids.toBytes(message.tenantId()), Uuids.toBytes(message.ticketId()), type).stream().findFirst()
                    .orElseThrow(() -> duplicate);
            if (!clockId.equals(raced.clockId()) || !aggregate.slaPolicyId().equals(raced.slaPolicyId())
                    || !started.equals(raced.startedAt())
                    || !deadline.equals(raced.deadlineAt()) || !Objects.equals(reminderAt, raced.reminderDueAt())
                    || duration.longValue() != raced.duration() || !Objects.equals(reminder, raced.reminder())) {
                throw new IllegalStateException("Workflow reverse SLA clock identity/content conflict", duplicate);
            }
        }
    }

    /** 反向事件恢复与数据库提交后 SLA 投影共同使用的稳定身份。 */


    public void insertTimeline(AcceptedWorkflowReverse message) {
        insertTimeline(message, message.businessOccurredAt());
    }

    /**
     * 记录工单业务时间线，使用稳定副作用键防止重复消费重复追加。
     */
    public void insertTimeline(AcceptedWorkflowReverse message, Instant recordedAt) {
        String event = "PASSED".equals(message.outcome()) ? "APPROVAL_PASSED" : "APPROVAL_REJECTED";
        String summary = "PASSED".equals(message.outcome()) ? "审批已通过。" : "审批已拒绝。";
        if (message.decisionReason() != null) summary = message.decisionReason();
        byte[] effectKey = WorkflowReverseIdentity.finalizationEffectKey(message);
        UUID timelineId = IdempotencyCodec.uuidFromDigest(effectKey);
        Timeline existing = findTimeline(message, effectKey).orElse(null);
        if (existing != null) {
            if (!sameTimeline(existing, timelineId, message, event, summary, effectKey)) {
                throw new IllegalStateException("Workflow reverse timeline identity/content conflict");
            }
            return;
        }
        try {
            mapper.insertTimelineUpdate(Uuids.toBytes(message.tenantId()), Uuids.toBytes(timelineId), Uuids.toBytes(message.ticketId()), effectKey, event, summary, Uuids.toBytes(message.workflowInstanceId()), Timestamp.from(message.businessOccurredAt()), Uuids.toBytes(message.correlationId()), Uuids.toBytes(message.causationId()), Timestamp.from(recordedAt));
        }
        catch (DuplicateKeyException duplicate) {
            Timeline raced = findTimeline(message, effectKey).orElseThrow(() -> duplicate);
            if (!sameTimeline(raced, timelineId, message, event, summary, effectKey)) {
                throw new IllegalStateException("Workflow reverse timeline identity/content conflict", duplicate);
            }
        }
    }

    /**
     * 按稳定副作用键读取时间线，供重复反向消息内容核验。
     */
    private Optional<Timeline> findTimeline(AcceptedWorkflowReverse message, byte[] effectKey) {
        requireTenant(message.tenantId());
        return mapper.findTimelineSelect(Uuids.toBytes(message.tenantId()), Uuids.toBytes(message.ticketId()), effectKey).stream().findFirst();
    }

    /**
     * 比较既有时间线业务内容，防止重复消息引入不同效果或原因。
     */
    private static boolean sameTimeline(Timeline row, UUID timelineId,
                                        AcceptedWorkflowReverse message, String event,
                                        String summary, byte[] effectKey) {
        return timelineId.equals(row.timelineId())
                && Objects.equals(row.eventType(), event)
                && Objects.equals(row.summary(), summary)
                && Objects.equals(row.relatedRefType(), "WORKFLOW_INSTANCE")
                && message.workflowInstanceId().equals(row.relatedRefId())
                && Objects.equals(row.actorType(), "SERVICE")
                && row.actorId() == null
                && Arrays.equals(row.effectKey(), effectKey)
                && Objects.equals(row.occurredAt(), message.businessOccurredAt())
                && Objects.equals(row.correlationId(), message.correlationId())
                && Objects.equals(row.causationId(), message.causationId());
    }

    /**
     * 确定性的终结副作用身份：普通 PASSED/REJECTED 沿用 M6 键结构，
     * conditionResult=false 使用独立后缀，避免自动通过与真实审批错误合并。
     */


    public void insertProcessedInbox(AcceptedWorkflowReverse message, UUID inboxId, Instant now) {
        insertInbox(message, inboxId, "PROCESSED", "TICKET", message.ticketId(), 1,
                null, now, now);
    }

    /**
     * 保存拒绝消息结果与错误码，避免毒消息反复执行业务。
     */
    public void insertRejectedInbox(AcceptedWorkflowReverse message, UUID inboxId,
                                    String code, Instant now) {
        insertInbox(message, inboxId, "REJECTED", null, null, 1, code, now, now);
    }

    /**
     * 保存待恢复 Inbox，保留原始消息供受控重放。
     */
    public void insertRecoveryInbox(AcceptedWorkflowReverse message, UUID inboxId,
                                    String code, Instant now) {
        insertInbox(message, inboxId, "RECOVERY_REQUIRED", null, null,
                WorkflowReverseContract.DELIVERY_ATTEMPTS, code, now, null);
    }

    /**
     * 保存消息接收身份与状态，为重复投递及恢复提供一致锚点。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     */
    private void insertInbox(AcceptedWorkflowReverse message, UUID inboxId, String status,
                             String resultType, UUID resultId, int attempts, String error,
                             Instant now, Instant processedAt) {
        requireTransaction();
        UUID stableInboxId = IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(
                "FD-WORKFLOW-REVERSE-INBOX-V1", message.tenantId(), message.messageId()));
        if (inboxId != null && !stableInboxId.equals(inboxId)) {
            throw new IllegalStateException("Workflow reverse Inbox identity is not deterministic");
        }
        try {
            mapper.insertInboxUpdate(Uuids.toBytes(message.tenantId()), Uuids.toBytes(stableInboxId), WorkflowReverseContract.PRODUCER, Uuids.toBytes(message.messageId()), message.businessIntentKey(), message.binding().contractRef(), WorkflowReverseContract.VERSION, message.bodyBytes(), message.bodySha256(), Uuids.toBytes(message.correlationId()), Uuids.toBytes(message.causationId()), Timestamp.from(message.envelopeOccurredAt()), status, resultType, Uuids.toBytes(resultId), attempts, error, Timestamp.from(now), Timestamp.from(now), timestamp(processedAt), Timestamp.from(now), Timestamp.from(now));
        }
        catch (DuplicateKeyException duplicate) {
            Inbox existing = findInbox(message.tenantId(), stableInboxId, false)
                    .orElseThrow(() -> duplicate);
            if (!sameSource(message, existing)) {
                throw new IllegalStateException("Workflow reverse Inbox identity/content conflict", duplicate);
            }
        }
    }

    /**
     * 恢复成功后条件更新 Inbox 为已处理，并关联最终结果。
     */
    public boolean completeRecoveredInbox(AcceptedWorkflowReverse message, Inbox inbox, Instant now) {
        int updated = mapper.completeRecoveredInboxUpdate(Uuids.toBytes(message.ticketId()), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Uuids.toBytes(message.tenantId()), Uuids.toBytes(inbox.inboxId()), inbox.lockVersion());
        if (updated == 1) {
            resolveRecovery(message.tenantId(), inbox.inboxId(), now);
            return true;
        }
        // 并发重放可能已提交同一终态，只有不可变结果完全相同才算幂等成功。
        Inbox current = findInbox(message.tenantId(), inbox.inboxId(), true)
                .orElseThrow(() -> new IllegalStateException("Recovered Ticket Inbox disappeared"));
        if ("PROCESSED".equals(current.status())
                && "TICKET".equals(current.resultType())
                && message.ticketId().equals(current.resultId())
                && sameSource(message, current)) {
            resolveRecovery(message.tenantId(), inbox.inboxId(), now);
            return false;
        }
        throw new IllegalStateException("Recovered Ticket Inbox completion CAS/content conflict");
    }

    /**
     * 将恢复中的 Inbox 收敛为已拒绝，保留不可继续处理的原因。
     */
    public boolean rejectRecoveredInbox(AcceptedWorkflowReverse message, Inbox inbox,
                                        String code, Instant now) {
        int updated = mapper.rejectRecoveredInboxUpdate(code, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Uuids.toBytes(message.tenantId()), Uuids.toBytes(inbox.inboxId()), inbox.lockVersion());
        if (updated == 1) {
            resolveRecovery(message.tenantId(), inbox.inboxId(), now);
            return true;
        }
        Inbox current = findInbox(message.tenantId(), inbox.inboxId(), true)
                .orElseThrow(() -> new IllegalStateException("Recovered Ticket Inbox disappeared"));
        if ("REJECTED".equals(current.status()) && current.resultType() == null
                && current.resultId() == null && Objects.equals(code, current.lastErrorCode())
                && sameSource(message, current)) {
            resolveRecovery(message.tenantId(), inbox.inboxId(), now);
            return false;
        }
        throw new IllegalStateException("Recovered Ticket Inbox rejection CAS/content conflict");
    }

    /**
     * 保存消息恢复记录和来源关联，供查询与人工重放。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 唯一约束竞争时回读既有事实并核验内容，避免重复产生业务效果。
     */
    public void insertRecovery(AcceptedWorkflowReverse message, UUID inboxId, UUID recoveryId,
                               String code, String summary, Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        UUID stableInboxId = IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(
                "FD-WORKFLOW-REVERSE-INBOX-V1", message.tenantId(), message.messageId()));
        UUID stableRecoveryId = IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(
                "FD-WORKFLOW-REVERSE-RECOVERY-V1", message.tenantId(), stableInboxId));
        if (inboxId != null && !stableInboxId.equals(inboxId)) {
            throw new IllegalStateException("Workflow reverse Recovery source identity is not deterministic");
        }
        if (recoveryId != null && !stableRecoveryId.equals(recoveryId)) {
            throw new IllegalStateException("Workflow reverse Recovery identity is not deterministic");
        }
        try {
            mapper.insertRecoveryUpdate(Uuids.toBytes(message.tenantId()), Uuids.toBytes(stableRecoveryId), Uuids.toBytes(stableInboxId), message.businessIntentKey(), WorkflowReverseContract.DELIVERY_ATTEMPTS, code, summary, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        }
        catch (DuplicateKeyException duplicate) {
            RecoveryIdentity existing = mapper.insertRecoverySelect2(Uuids.toBytes(message.tenantId()), Uuids.toBytes(stableInboxId)).stream().findFirst().orElseThrow(() -> duplicate);
            if (!stableRecoveryId.equals(existing.recoveryId())
                    || !Arrays.equals(message.businessIntentKey(), existing.intent())
                    || !"OPEN".equals(existing.state())
                    || existing.attemptCount() != WorkflowReverseContract.DELIVERY_ATTEMPTS
                    || !Objects.equals(code, existing.errorCode())
                    || !Objects.equals(summary, existing.summary())) {
                throw new IllegalStateException("Conflicting Workflow reverse recovery identity", duplicate);
            }
        }
    }

    /**
     * 完成已满足收敛条件的恢复项，避免再次重放终态事实。
     */
    private void resolveRecovery(UUID tenantId, UUID inboxId, Instant now) {
        RecoveryState current = mapper.resolveRecoverySelect(Uuids.toBytes(tenantId), Uuids.toBytes(inboxId)).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Workflow reverse recovery row missing"));
        if ("RESOLVED".equals(current.state())) return;
        if (!Set.of("OPEN", "REPLAYING").contains(current.state())) {
            throw new IllegalStateException("Workflow reverse recovery state conflict: " + current.state());
        }
        int updated = mapper.resolveRecoveryUpdate2(Timestamp.from(now), Timestamp.from(now), Uuids.toBytes(tenantId), Uuids.toBytes(current.recoveryId()), current.state(), current.lockVersion());
        requireOne(updated, "Workflow reverse recovery resolution");
    }

    /**
     * 按租户与消息身份读取 Inbox，供重复消费或恢复结算核验。
     */
    private Optional<Inbox> findInbox(UUID tenantId, UUID inboxId, boolean lock) {
        requireTenant(tenantId);
        return mapper.findInboxSelect(Uuids.toBytes(tenantId), Uuids.toBytes(inboxId), lock).stream().findFirst();
    }

    /**
     * 保存业务操作审计，记录目标、主体、结果和请求链路。
     */
    public void insertAudit(AcceptedWorkflowReverse message, UUID targetId, String action,
                            String outcome, String reason, String error, Instant now) {
        insertAudit(message, WorkflowReverseIdentity.auditId(message, action, targetId, outcome, reason, error),
                targetId, action, outcome, reason, error, now);
    }

    /**
     * 保存业务操作审计，记录目标、主体、结果和请求链路。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 唯一约束竞争时回读既有事实并核验内容，避免重复产生业务效果。
     */
    public void insertAudit(AcceptedWorkflowReverse message, UUID auditId, UUID targetId,
                            String action, String outcome, String reason, String error,
                            Instant now) {
        requireTenant(message.tenantId());
        requireTransaction();
        try {
            mapper.insertAuditUpdate(Uuids.toBytes(message.tenantId()), Uuids.toBytes(auditId), action, Uuids.toBytes(targetId), outcome, reason, error, Uuids.toBytes(message.correlationId()), Uuids.toBytes(message.causationId()), Timestamp.from(now));
        }
        catch (DuplicateKeyException duplicate) {
            Audit existing = findAudit(message, auditId).orElseThrow(() -> duplicate);
            if (!sameAudit(existing, auditId, targetId, action, outcome, reason, error, message)) {
                throw new IllegalStateException("Workflow reverse audit identity/content conflict", duplicate);
            }
        }
    }

    /**
     * 比较来源身份、原文和摘要，防止不同消息共用一条幂等记录。
     */
    private static boolean sameSource(AcceptedWorkflowReverse message, Inbox row) {
        return message.messageId().equals(row.messageId())
                && message.binding().contractRef().equals(row.contractRef())
                && row.contractVersion() == WorkflowReverseContract.VERSION
                && Arrays.equals(message.businessIntentKey(), row.intent())
                && Arrays.equals(message.bodyBytes(), row.body())
                && Arrays.equals(message.bodySha256(), row.bodyHash())
                && Objects.equals(message.correlationId(), row.correlationId())
                && Objects.equals(message.causationId(), row.causationId())
                && Objects.equals(message.envelopeOccurredAt(), row.occurredAt());
    }

    /**
     * 按稳定身份读取既有审计，供重复处理时核对业务内容。
     */
    public Optional<Audit> findAudit(AcceptedWorkflowReverse message, UUID auditId) {
        requireTenant(message.tenantId());
        return mapper.findAuditSelect(Uuids.toBytes(message.tenantId()), Uuids.toBytes(auditId))
                .stream().findFirst();
    }



    /**
     * 比较已有审计与预期业务内容，拒绝不同意图复用同一身份。
     */
    public boolean sameAudit(Audit row, UUID auditId, UUID targetId, String action,
                             String outcome, String reason, String error,
                             AcceptedWorkflowReverse message) {
        return auditId.equals(row.auditId())
                && Objects.equals(row.actionCode(), action)
                && Objects.equals(row.targetType(), "TICKET")
                && Objects.equals(row.targetId(), targetId)
                && Objects.equals(row.outcome(), outcome)
                && Objects.equals(row.actorType(), "SERVICE")
                && row.actorId() == null
                && Objects.equals(row.reason(), reason)
                && Objects.equals(row.errorCode(), error)
                && Objects.equals(row.correlationId(), message.correlationId())
                && Objects.equals(row.causationId(), message.causationId());
    }

    /**
     * 校验传入租户与当前上下文一致，阻止跨租户持久化操作。
     */
    private static void requireTenant(UUID tenantId) {
        TenantContextHolder.current().requireTenant(tenantId);
    }
    /**
     * 要求调用处于数据库事务中，保证关联事实可以原子提交或回滚。
     */
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Workflow reverse mutation requires a transaction");
        }
    }
    /**
     * 要求条件更新影响一行，否则按状态变化或并发竞争处理。
     */
    private static void requireOne(int updated, String operation) {
        if (updated != 1) throw new IllegalStateException(operation + " lost its single writer");
    }
    /**
     * 解析时间文本或转换数据库时间值，遵循空值约定并保留业务精度。
     */
    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }











}
