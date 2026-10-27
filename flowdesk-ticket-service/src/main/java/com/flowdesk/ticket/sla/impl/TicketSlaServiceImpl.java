package com.flowdesk.ticket.sla.impl;

import com.flowdesk.ticket.model.sla.SlaPolicyRow;
import com.flowdesk.ticket.sla.*;

import com.flowdesk.ticket.sla.SlaClockService;
import com.flowdesk.ticket.sla.SlaPersistenceMapper;
import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.timestamp;
import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.requireTransaction;
import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.requireTenant;
import java.util.Collection;
import java.sql.Timestamp;
import org.springframework.dao.DuplicateKeyException;
import com.flowdesk.ticket.model.sla.*;
import com.flowdesk.ticket.mapper.*;
import com.flowdesk.ticket.model.*;
import com.flowdesk.ticket.persistence.Uuids;

import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.ticket.notification.TicketNotificationIntent;
import com.flowdesk.ticket.notification.TicketNotificationOutboxWriter;

import com.flowdesk.ticket.service.IdempotencyCodec;
import com.flowdesk.ticket.service.TicketSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 管理 SLA 时钟生命周期、触发事实及通知意图。
 * 用户操作加入已有事务；调度入口统一先锁工单、后锁 SLA 时钟，以保持并发锁顺序一致。
 */
@Service
public class TicketSlaServiceImpl implements TicketSlaService {
    private final TicketAuditMapper ticketAuditMapper;
    private final TicketMapper ticketMapper;
    private final TicketOutboxWriteMapper ticketOutboxWriteMapper;
    private final TicketTimelineMapper ticketTimelineMapper;
    private final SlaPersistenceMapper slaMapper;
    private final SlaClockService slaClockService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final TicketNotificationOutboxWriter notificationWriter;
    private final SlaTargetConfigurationGate slaTargetConfigurationGate;
    private final SlaIndexProjection slaIndexProjection;

    /**
     * 构造SLA组件并保存调用依赖；支持注入时钟以控制时间边界。
     */
    public TicketSlaServiceImpl(TicketAuditMapper ticketAuditMapper, TicketMapper ticketMapper, TicketOutboxWriteMapper ticketOutboxWriteMapper, TicketTimelineMapper ticketTimelineMapper, SlaPersistenceMapper slaMapper, SlaClockService slaClockService,
                            PlatformTransactionManager transactionManager, Clock clock,
                            TicketNotificationOutboxWriter notificationWriter,
                            SlaTargetConfigurationGate slaTargetConfigurationGate,
                            SlaIndexProjection slaIndexProjection) {
        this.ticketAuditMapper = ticketAuditMapper;
        this.ticketMapper = ticketMapper;
        this.ticketOutboxWriteMapper = ticketOutboxWriteMapper;
        this.ticketTimelineMapper = ticketTimelineMapper;
        this.slaMapper = slaMapper;
        this.slaClockService = slaClockService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.notificationWriter = notificationWriter;
        this.slaTargetConfigurationGate = slaTargetConfigurationGate;
        this.slaIndexProjection = slaIndexProjection;
    }

    /**
     * 在短事务内处理一个到期候选。
     * 调用方必须先恢复持久任务的租户上下文，并统一先锁工单再锁时钟，使调度与用户操作保持相同锁顺序。
     */
    public SlaProcessResult processSlaCandidate(UUID tenantId, UUID ticketId, UUID slaClockId,
                                                String stage, Instant suppliedNow) {
        TenantContext context = TicketSupport.context();
        context.requireTenant(tenantId);
        if (ticketId == null || slaClockId == null
                || !("REMINDER".equals(stage) || "BREACH".equals(stage))) {
            return new SlaProcessResult(false, false, "INVALID_CANDIDATE");
        }
        Instant now = suppliedNow == null ? now() : suppliedNow.truncatedTo(ChronoUnit.MICROS);
        SlaProcessResult result = transactionTemplate.execute(tx -> {
            TicketRow ticket = ticketMapper.findTicketForUpdate(tenantId, ticketId)
                    .orElse(null);
            if (ticket == null) {
                return new SlaProcessResult(false, false, "TICKET_NOT_FOUND");
            }
            com.flowdesk.ticket.model.sla.SlaClockRow clock = findSlaClockByIdForUpdate(
                    tenantId, slaClockId).orElse(null);
            if (clock == null || !ticketId.equals(clock.ticketId())) {
                return new SlaProcessResult(false, false, "CLOCK_NOT_FOUND");
            }
            scheduleSlaIndexProjection(context.tenantId(), clock.slaClockId());
            return triggerSlaStage(context, ticket, clock, stage, now);
        });
        return result == null ? new SlaProcessResult(false, false, "NO_RESULT") : result;
    }

    /** 即使时钟已终态，也可根据既有触发事实补全尚未落定的收件人和通知。 */
    public void retrySlaNotification(UUID tenantId, UUID ticketId, UUID clockId, String stage, Instant now) {
        var context = TicketSupport.context();
        context.requireTenant(tenantId);
        transactionTemplate.executeWithoutResult(tx -> {
            var ticket = ticketMapper.findTicketForUpdate(tenantId, ticketId).orElse(null);
            var clock = findSlaClockByIdForUpdate(tenantId, clockId).orElse(null);
            if (ticket != null && clock != null && ticketId.equals(clock.ticketId())
                    && findSlaTrigger(tenantId, clockId, stage, false).isPresent()) {
                ensureSlaTrigger(context, ticket, clock, stage, now);
            }
        });
    }

    /**
     * 确定提醒或超时效果，原子保存触发、时间线与通知意图。
     */
    private SlaProcessResult triggerSlaStage(TenantContext context,
                                             TicketRow ticket,
                                             com.flowdesk.ticket.model.sla.SlaClockRow clock,
                                             String stage, Instant now) {
        if (!"RUNNING".equals(clock.status())) {
            if (findSlaTrigger(context.tenantId(), clock.slaClockId(), stage, false).isPresent()) {
                ensureSlaTrigger(context, ticket, clock, stage, now);
            }
            return new SlaProcessResult(false, false, "CLOCK_TERMINAL");
        }
        if ("REMINDER".equals(stage)) {
            if (clock.reminderDueAt() == null || now.isBefore(clock.reminderDueAt())) {
                return new SlaProcessResult(false, false, "REMINDER_NOT_DUE");
            }
            if (now.isAfter(clock.deadlineAt())) {
                appendReminderMissedAudit(context, clock, now);
                return new SlaProcessResult(false, true, "REMINDER_MISSED_AFTER_DEADLINE");
            }
            boolean inserted = ensureSlaTrigger(context, ticket, clock, stage, now);
            return new SlaProcessResult(inserted, inserted, inserted ? "REMINDER_TRIGGERED" : "REMINDER_DUPLICATE");
        }

        // 严格超过截止时间才算超时，恰好等于截止时间仍算准时。
        if (!now.isAfter(clock.deadlineAt())) {
            return new SlaProcessResult(false, false, "BREACH_NOT_DUE");
        }
        requireOneUpdate(slaClockService.updateSlaClockBreached(context.tenantId(),
                clock.slaClockId(), clock.lockVersion(), now), clock.clockType());
        com.flowdesk.ticket.model.sla.SlaClockRow breached = findSlaClockByIdForUpdate(
                context.tenantId(), clock.slaClockId()).orElseThrow(
                        () -> new IllegalStateException("SLA clock disappeared after breach"));
        boolean inserted = ensureSlaTrigger(context, ticket, breached, stage, now);
        return new SlaProcessResult(inserted, inserted, inserted ? "BREACH_TRIGGERED" : "BREACH_DUPLICATE");
    }

    /** 在工单事务内按实际接受时间完成 SLA；准时、已超时与本次才发现超时分别收敛。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void satisfyClock(TenantContext context, UUID ticketId, String clockType, Instant now) {
        // 调用方已更新并锁住工单，再锁时钟；始终使用工单侧接受时间，不采用流程事件时间或客户端时间。
        com.flowdesk.ticket.model.sla.SlaClockRow clock = findSlaClockForUpdate(
                        context.tenantId(), ticketId, clockType)
                .orElseThrow(() -> new IllegalStateException("SLA clock is missing: " + clockType));
        scheduleSlaIndexProjection(context.tenantId(), clock.slaClockId());
        if ("SATISFIED_ON_TIME".equals(clock.status()) || "SATISFIED_LATE".equals(clock.status())) {
            return;
        }
        if ("BREACHED".equals(clock.status())) {
            ensureSlaTrigger(context, ticketMapper.findTicket(context.tenantId(), ticketId).orElseThrow(),
                    clock, "BREACH", now);
            requireOneUpdate(slaClockService.updateSlaClockSatisfiedLate(context.tenantId(),
                    clock.slaClockId(), clock.lockVersion(), "USER", TicketSupport.actorId(), now), clockType);
            appendSlaMilestoneFacts(context, clock, "SLA_SATISFIED_LATE", "USER",
                    TicketSupport.actorId(), now);
            return;
        }
        if (!now.isAfter(clock.deadlineAt())) {
            requireOneUpdate(slaClockService.updateSlaClockSatisfiedOnTime(context.tenantId(),
                    clock.slaClockId(), clock.lockVersion(), "USER", TicketSupport.actorId(), now), clockType);
            appendSlaMilestoneFacts(context, clock, "SLA_SATISFIED_ON_TIME", "USER",
                    TicketSupport.actorId(), now);
            return;
        }

        // 迟到的用户操作可能先于扫描器把 RUNNING 推为 BREACHED。
        // 同一事务内保留不可变超时触发，再推进为 SATISFIED_LATE，外部看不到中间提交状态。
        requireOneUpdate(slaClockService.updateSlaClockBreached(context.tenantId(),
                clock.slaClockId(), clock.lockVersion(), "USER", TicketSupport.actorId(), now), clockType);
        com.flowdesk.ticket.model.sla.SlaClockRow breached = findSlaClockByIdForUpdate(
                context.tenantId(), clock.slaClockId()).orElseThrow();
        TicketRow ticket = ticketMapper.findTicket(context.tenantId(), ticketId).orElseThrow();
        ensureSlaTrigger(context, ticket, breached, "BREACH", now);
        requireOneUpdate(slaClockService.updateSlaClockSatisfiedLate(context.tenantId(),
                breached.slaClockId(), breached.lockVersion(), "USER", TicketSupport.actorId(), now), clockType);
        appendSlaMilestoneFacts(context, breached, "SLA_SATISFIED_LATE", "USER",
                TicketSupport.actorId(), now);
    }

    /** 每个时钟阶段只有一份确定性触发事实，重复执行核对原身份并补齐时间线、审计和通知。 */
    private boolean ensureSlaTrigger(TenantContext context, TicketRow ticket,
                                     com.flowdesk.ticket.model.sla.SlaClockRow clock, String stage, Instant now) {
        scheduleSlaIndexProjection(context.tenantId(), clock.slaClockId());
        byte[] effectKey = IdempotencyCodec.derivedKey(
                "FD-SLA-TRIGGER-V1", context.tenantId(), clock.slaClockId(), stage);
        byte[] publicationKey = IdempotencyCodec.derivedKey(
                "FD-SLA-TRIGGER-PUBLICATION-V1", context.tenantId(), clock.slaClockId(), stage);
        Instant dueAt = "REMINDER".equals(stage) ? clock.reminderDueAt() : clock.deadlineAt();
        if (dueAt == null) {
            return false;
        }
        Optional<com.flowdesk.ticket.model.sla.SlaTriggerDetailRow> existing = findSlaTrigger(
                context.tenantId(), clock.slaClockId(), stage, false);
        UUID triggerId = uuidFromKey(effectKey);
        boolean created = existing.isEmpty();
        List<UUID> recipients;
        boolean missingSnapshot = false;
        if (existing.isPresent()) {
            com.flowdesk.ticket.model.sla.SlaTriggerDetailRow row = existing.get();
            if (!Arrays.equals(effectKey, row.effectKey())
                    || !Arrays.equals(publicationKey, row.publicationKey())
                    || !dueAt.equals(row.dueAt())) {
                throw new IllegalStateException("SLA trigger identity/content conflict");
            }
            // 保留已确定的收件人集合；空集合表示仍待投递，资格恢复后可以补全。
            recipients = findSlaTriggerRecipientSnapshot(
                    context.tenantId(), triggerId);
            if (recipients.isEmpty()) {
                recipients = resolveSlaRecipients(context, ticket, clock, stage, now);
                if (!recipients.isEmpty()) slaClockService.insertSlaTriggerRecipientSnapshot(
                        context.tenantId(), triggerId, recipients, "SCHEDULER", null, now);
            }
            missingSnapshot = recipients.isEmpty();
            publicationKey = row.publicationKey();
        }
        else {
            slaClockService.insertSlaTrigger(context.tenantId(), triggerId, clock.slaClockId(),
                    stage, effectKey, dueAt, now, publicationKey);
            // 提醒不改变时钟生命周期状态，需显式推进更新时间高水位，以便重建追平时发现此次变化。
            if ("REMINDER".equals(stage)
                    && slaClockService.updateSlaClockUpdatedAt(context.tenantId(), clock.slaClockId(), now) != 1) {
                throw new IllegalStateException("SLA reminder clock touch changed an unexpected number of rows");
            }
            recipients = resolveSlaRecipients(context, ticket, clock, stage, now);
            if (!recipients.isEmpty()) {
                slaClockService.insertSlaTriggerRecipientSnapshot(context.tenantId(), triggerId,
                        recipients, "SCHEDULER", null, now);
            }
        }

        String event = "REMINDER".equals(stage)
                ? "SLA_REMINDER_TRIGGERED" : "SLA_BREACH_TRIGGERED";
        byte[] timelineKey = IdempotencyCodec.derivedKey(
                "FD-SLA-TRIGGER-TIMELINE-V1", context.tenantId(), clock.slaClockId(), stage);
        if (ticketTimelineMapper.findTimelineByEffectKey(context.tenantId(), ticket.ticketId(), timelineKey).isEmpty()) {
            ticketTimelineMapper.insertTimeline(context.tenantId(), uuidFromKey(timelineKey), ticket.ticketId(),
                    timelineKey, event, "SLA " + stage.toLowerCase(Locale.ROOT) + " 已触发。",
                    "SLA_CLOCK", clock.slaClockId(), "SCHEDULER", null, now,
                    context.correlationId(), null, now);
        }
        byte[] auditKey = IdempotencyCodec.derivedKey(
                "FD-SLA-TRIGGER-AUDIT-V1", context.tenantId(), clock.slaClockId(), stage);
        UUID auditId = uuidFromKey(auditKey);
        if (ticketAuditMapper.findAudit(context.tenantId(), auditId).isEmpty()) {
            ticketAuditMapper.insertAudit(context.tenantId(), auditId,
                    "SLA_" + stage + "_TRIGGERED", "SLA_CLOCK", clock.slaClockId(),
                    "SUCCEEDED", "SCHEDULER", null, "SLA trigger committed", null,
                    context.correlationId(), null, now, now);
        }
        if (missingSnapshot) {
            // 扫描器独立于时钟状态重试待投递通知。
            appendSlaNotificationSkipAudit(context, clock, stage,
                    "SLA_TRIGGER_RECIPIENT_SNAPSHOT_MISSING", now);
        }
        // 持久化的触发发布键是通知扇出的不可变根。
        // TicketNotificationIntent 据此为每个具体收件人派生独立副作用与发布身份。
        if (!recipients.isEmpty()) {
            writeSlaNotifications(context, ticket, clock, stage, publicationKey, recipients,
                    existing.map(com.flowdesk.ticket.model.sla.SlaTriggerDetailRow::triggeredAt).orElse(now));
        }
        return created;
    }

    /**
     * 核验 SLA 升级目标资格，形成当前触发的收件人集合。
     */
    private List<UUID> resolveSlaRecipients(TenantContext context,
                                             TicketRow ticket,
                                             com.flowdesk.ticket.model.sla.SlaClockRow clock,
                                             String stage, Instant now) {
        if (slaTargetConfigurationGate == null) {
            appendSlaNotificationSkipAudit(context, clock, stage,
                    "SLA_TARGET_GATE_UNAVAILABLE", now);
            return List.of();
        }
        // 在选定收件人时锁住具体目标，将用户引用复制为不可变触发快照。
        // 后续替换目标或转派工单均不能改写既定收件人。
        SlaTargetConfigurationGate.GateResult gate = slaTargetConfigurationGate
                .evaluate(context.tenantId(), now, true);
        if (!gate.open() || gate.recipientUserId() == null) {
            appendSlaNotificationSkipAudit(context, clock, stage,
                    gate.reasonCode() == null ? "SLA_TARGET_NOT_QUALIFIED" : gate.reasonCode(), now);
            return List.of();
        }
        Set<UUID> recipients = new java.util.LinkedHashSet<>();
        if (ticket.assigneeUserId() != null) {
            recipients.add(ticket.assigneeUserId());
        }
        if ("REMINDER".equals(stage) && recipients.isEmpty()) {
            recipients.add(gate.recipientUserId());
        }
        if ("BREACH".equals(stage)) {
            recipients.add(gate.recipientUserId());
        }
        return List.copyOf(recipients);
    }

    /**
     * 记录提醒时间已错过的事实，保留未发送提醒的原因。
     */
    private void appendReminderMissedAudit(TenantContext context,
                                           com.flowdesk.ticket.model.sla.SlaClockRow clock, Instant now) {
        UUID auditId = uuidFromKey(IdempotencyCodec.derivedKey(
                "FD-SLA-REMINDER-MISSED-AUDIT-V1", context.tenantId(), clock.slaClockId()));
        if (ticketAuditMapper.findAudit(context.tenantId(), auditId).isEmpty()) {
            ticketAuditMapper.insertAudit(context.tenantId(), auditId,
                    "SLA_REMINDER_MISSED_AFTER_DEADLINE", "SLA_CLOCK", clock.slaClockId(),
                    "SUCCEEDED", "SCHEDULER", null,
                    "Reminder window closed before the scanner observed it: "
                            + "REMINDER_MISSED_AFTER_DEADLINE", null,
                    context.correlationId(), null, now, now);
        }
    }

    /**
     * 追加 SLA 阶段时间线与审计，保持业务进度可追溯。
     */
    private void appendSlaMilestoneFacts(TenantContext context, com.flowdesk.ticket.model.sla.SlaClockRow clock,
                                         String event, String actorType, UUID actorId, Instant now) {
        byte[] effectKey = IdempotencyCodec.derivedKey(
                "FD-SLA-MILESTONE-TIMELINE-V1", context.tenantId(), clock.slaClockId(), event);
        TicketRow ticket = ticketMapper.findTicket(context.tenantId(), clock.ticketId()).orElseThrow();
        if (ticketTimelineMapper.findTimelineByEffectKey(context.tenantId(), ticket.ticketId(), effectKey).isEmpty()) {
            ticketTimelineMapper.insertTimeline(context.tenantId(), uuidFromKey(effectKey), ticket.ticketId(),
                    effectKey, event, "SLA 里程碑已接受。", "SLA_CLOCK", clock.slaClockId(),
                    actorType, actorId, now, context.correlationId(), null, now);
        }
        byte[] auditKey = IdempotencyCodec.derivedKey(
                "FD-SLA-MILESTONE-AUDIT-V1", context.tenantId(), clock.slaClockId(), event);
        UUID auditId = uuidFromKey(auditKey);
        if (ticketAuditMapper.findAudit(context.tenantId(), auditId).isEmpty()) {
            ticketAuditMapper.insertAudit(context.tenantId(), auditId, event, "SLA_CLOCK",
                    clock.slaClockId(), "SUCCEEDED", actorType, actorId,
                    "SLA milestone accepted", null, context.correlationId(), null, now, now);
        }
    }

    /**
     * 按持久化触发与收件人快照写入通知，使用稳定发布键去重。
     */
    private void writeSlaNotifications(TenantContext context, TicketRow ticket,
                                       com.flowdesk.ticket.model.sla.SlaClockRow clock, String stage,
                                       byte[] triggerEffectKey, List<UUID> recipients,
                                       Instant now) {
        if (notificationWriter == null) {
            return;
        }
        for (UUID recipient : recipients) {
            writeNotification(context, recipient,
                    "REMINDER".equals(stage) ? TicketNotificationIntent.SLA_REMINDER
                            : TicketNotificationIntent.SLA_BREACH,
                    triggerEffectKey, clock.slaClockId(),
                    "REMINDER".equals(stage) ? "工单处理期限提醒" : "工单处理已超时",
                    "第 " + (clock.cycleNo() + 1) + " 轮"
                            + ("FIRST_RESPONSE".equals(clock.clockType()) ? "响应" : "解决")
                            + ("REMINDER".equals(stage) ? "已达到提醒时间。" : "已超过处理期限。")
                            + "请查看工单当前状态。", now,
                    "SLA_CLOCK");
        }
    }

    /**
     * 记录资格或配置不足导致的通知跳过，保留超时业务事实。
     */
    private void appendSlaNotificationSkipAudit(TenantContext context,
                                                com.flowdesk.ticket.model.sla.SlaClockRow clock,
                                                String stage, String reason, Instant now) {
        if (clock == null || stage == null) {
            return;
        }
        UUID auditId = uuidFromKey(IdempotencyCodec.derivedKey(
                "FD-SLA-NOTIFICATION-SKIP-AUDIT-V1", context.tenantId(), clock.slaClockId(),
                stage, reason));
        if (ticketAuditMapper.findAudit(context.tenantId(), auditId).isEmpty()) {
            ticketAuditMapper.insertAudit(context.tenantId(), auditId, "SLA_" + stage + "_NOTIFICATION_SKIPPED",
                    "SLA_CLOCK", clock.slaClockId(), "SUCCEEDED", "SCHEDULER", null,
                    "Notification skipped by configuration: " + reason, null,
                    context.correlationId(), null, now, now);
        }
    }

    /**
     * 确认条件更新成功，避免并发更新未命中后仍继续写副作用。
     */
    private static void requireOneUpdate(int updated, String clockType) {
        if (updated != 1) {
            throw new IllegalStateException("SLA clock transition lost the single-writer race: " + clockType);
        }
    }

    /** 在工单首次可处理时快照当前策略，创建首次响应与解决两个 SLA 时钟。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertClocks(TenantContext context, UUID ticketId, UUID slaPolicyId, Instant now) {
        com.flowdesk.ticket.model.sla.SlaPolicyRow policy = findSlaPolicy(
                        context.tenantId(), slaPolicyId)
                .orElseThrow(() -> new IllegalStateException("SLA policy is missing"));
        insertSlaClock(context, ticketId, policy, "FIRST_RESPONSE", now, now, policy.firstResponseSeconds(),
                policy.firstResponseReminderBeforeSeconds());
        insertSlaClock(context, ticketId, policy, "RESOLUTION", now, now, policy.resolutionSeconds(),
                policy.resolutionReminderBeforeSeconds());
    }

    /**
     * 保存时钟与策略时间快照，重复写入须与已有身份及内容一致。
     */
    private void insertSlaClock(TenantContext context, UUID ticketId, com.flowdesk.ticket.model.sla.SlaPolicyRow policy,
                                String clockType, Instant startedAt, Instant now, long durationSeconds,
                                Long reminderBeforeSeconds) {
        Instant deadline = startedAt.plusSeconds(durationSeconds);
        Instant reminderDue = reminderBeforeSeconds == null ? null
                : deadline.minusSeconds(reminderBeforeSeconds);
        UUID clockId = uuidFromKey(IdempotencyCodec.derivedKey(
                "FD-TICKET-SLA-CLOCK-V1", context.tenantId(), ticketId, clockType));
        slaClockService.insertSlaClock(context.tenantId(), clockId, ticketId,
                policy.slaPolicyId(), clockType, startedAt, deadline, reminderDue,
                durationSeconds, reminderBeforeSeconds, now);
        scheduleSlaIndexProjection(context.tenantId(), clockId);
    }

    /**
     * 登记提交后的 SLA 索引投影，避免回滚事务泄露未提交候选。
     */
    private void scheduleSlaIndexProjection(UUID tenantId, UUID slaClockId) {
        if (slaIndexProjection != null) {
            slaIndexProjection.schedule(tenantId, slaClockId);
        }
    }

    /** 从上一轮不可变策略快照启动新的解决周期，保留此前时钟与触发历史。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reopenResolutionClock(TenantContext context, UUID ticketId, int cycleNo, Instant now) {
        var previousClock = findSlaClockForUpdate(context.tenantId(), ticketId, "RESOLUTION")
                .orElseThrow(() -> new IllegalStateException("Resolved ticket is missing its resolution clock"));
        UUID reopenedClockId = uuidFromKey(IdempotencyCodec.derivedKey(
                "FD-TICKET-SLA-REOPEN-V1", context.tenantId(), ticketId, cycleNo));
        Instant deadline = now.plusSeconds(previousClock.policyDurationSeconds());
        Instant reminder = previousClock.policyReminderBeforeSeconds() == null ? null
                : deadline.minusSeconds(previousClock.policyReminderBeforeSeconds());
        slaClockService.insertSlaClock(context.tenantId(), reopenedClockId, ticketId,
                previousClock.slaPolicyId(), "RESOLUTION", now, deadline, reminder,
                previousClock.policyDurationSeconds(), previousClock.policyReminderBeforeSeconds(), cycleNo, now);
        scheduleSlaIndexProjection(context.tenantId(), reopenedClockId);
    }

    /**
     * 组织业务通知意图并写入发件箱，使通知发布与业务事实保持一致。
     */
    private void writeNotification(TenantContext context, UUID recipientUserId, String kind,
                                   byte[] sourceEffectKey, UUID sourceRefId,
                                   String title, String body, Instant occurredAt,
                                   String sourceRefType) {
        if (notificationWriter == null || recipientUserId == null) {
            return;
        }
        // 已发布 SLA 消息在应用升级后仍保留原始文案，由既有 Outbox 记录负责投递和 Broker 重试。
        if ("SLA_CLOCK".equals(sourceRefType) && ticketOutboxWriteMapper.hasNotificationPublication(context.tenantId(),
                TicketNotificationIntent.deriveChildPublicationKey(context.tenantId(), sourceEffectKey, recipientUserId, kind), sourceRefId)) return;
        UUID relatedTicketId = "TICKET".equals(sourceRefType) ? sourceRefId
                : findSlaClockById(context.tenantId(), sourceRefId)
                        .map(com.flowdesk.ticket.model.sla.SlaClockRow::ticketId).orElse(null);
        if (relatedTicketId != null) {
            var related = ticketMapper.findTicket(context.tenantId(), relatedTicketId).orElse(null);
            if (related != null) body = related.ticketNo() + " · " + related.subject() + "\n" + body;
        }
        TicketNotificationIntent.Intent intent = TicketNotificationIntent.create(
                context.tenantId(), recipientUserId, kind, sourceEffectKey,
                sourceRefType, sourceRefId, title, body, context.correlationId(), null,
                occurredAt);
        notificationWriter.write(context.tenantId(), intent);
    }

    /**
     * 取得当前时间并规范为业务使用的时间精度。
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 将稳定幂等键转为 UUID，保持业务事实与关联记录身份一致。
     */
    private static UUID uuidFromKey(byte[] key) {
        byte[] first = Arrays.copyOf(key, 16);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(first);
        return new UUID(buffer.getLong(), buffer.getLong());
    }



    /**
     * 按租户读取 SLA 策略，供创建时钟生成固定时长和提醒快照。
     */
    private Optional<SlaPolicyRow> findSlaPolicy(UUID tenantId, UUID slaPolicyId) {
        return slaMapper.findSlaPolicySelect(Uuids.toBytes(tenantId), Uuids.toBytes(slaPolicyId))
                .stream().findFirst();
    }

    /**
     * 按工单和类型锁定时钟，供业务达成目标时结算。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 传入租户必须与当前上下文一致。
     */
    private Optional<SlaClockRow> findSlaClockForUpdate(UUID tenantId, UUID ticketId,
                                                       String clockType) {
        requireTenant(tenantId);
        requireTransaction();
        return slaMapper.findSlaClockForUpdateSelect(Uuids.toBytes(tenantId), Uuids.toBytes(ticketId), clockType)
                .stream().findFirst();
    }

    /**
     * 在事务中锁定指定时钟，防止与并发阶段结算交叉。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 传入租户必须与当前上下文一致。
     */
    private Optional<SlaClockRow> findSlaClockByIdForUpdate(UUID tenantId, UUID slaClockId) {
        requireTenant(tenantId);
        requireTransaction();
        return slaMapper.findSlaClockByIdForUpdateSelect(Uuids.toBytes(tenantId), Uuids.toBytes(slaClockId))
                .stream().findFirst();
    }

    /**
     * 按当前租户和标识读取 SLA 时钟，无匹配时返回空结果。
     */
    private Optional<SlaClockRow> findSlaClockById(UUID tenantId, UUID slaClockId) {
        requireTenant(tenantId);
        return slaMapper.findSlaClockByIdSelect(Uuids.toBytes(tenantId), Uuids.toBytes(slaClockId))
                .stream().findFirst();
    }

    /**
     * 读取指定时钟与阶段的触发事实，供去重和通知重试。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 传入租户必须与当前上下文一致。
     */
    private Optional<SlaTriggerDetailRow> findSlaTrigger(UUID tenantId, UUID slaClockId, String stage,
                                                        boolean lock) {
        requireTenant(tenantId);
        if (lock) requireTransaction();
        return slaMapper.findSlaTriggerSelect(Uuids.toBytes(tenantId), Uuids.toBytes(slaClockId), stage, lock).stream().findFirst();
    }

    /**
     * 读取首次触发确定的收件人，避免重试受后续配置变化影响。
     * 传入租户必须与当前上下文一致。
     */
    private List<UUID> findSlaTriggerRecipientSnapshot(UUID tenantId, UUID triggerId) {
        requireTenant(tenantId);
        // 收件人表是不可变子集合，运行账号仅有 SELECT/INSERT 权限。
        // 调用方已通过父工单与时钟锁串行化写入，子表再加 FOR UPDATE 既多余，也违反真实 MySQL 的最小权限约束。
        return slaMapper.findSlaTriggerRecipientSnapshotSelect(Uuids.toBytes(tenantId), Uuids.toBytes(triggerId));
    }
}
