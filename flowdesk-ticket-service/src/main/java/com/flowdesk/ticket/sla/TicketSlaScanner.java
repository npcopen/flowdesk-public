package com.flowdesk.ticket.sla;

import com.flowdesk.ticket.sla.SlaClockService;
import com.flowdesk.ticket.sla.SlaPersistenceMapper;
import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.timestamp;
import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.requireTransaction;
import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.requireTenant;
import java.util.Optional;
import java.util.Collection;
import java.sql.Timestamp;
import org.springframework.dao.DuplicateKeyException;
import com.flowdesk.ticket.persistence.Uuids;
import com.flowdesk.ticket.model.sla.*;
import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按租户隔离、分批执行的 SLA 扫描器。
 * Redis 仅优化候选发现，每个候选均回到 TicketSlaService 与 MySQL 重新读取并条件裁决。
 */
public final class TicketSlaScanner {
    private static final Logger LOG = LoggerFactory.getLogger(TicketSlaScanner.class);
    private final SlaPersistenceMapper repository;
    private final SlaRebuildLeaseService leaseRepository;
    private final TicketSlaService slaService;
    private final SlaDueIndex index;
    private final TicketSlaProperties properties;
    private final Clock clock;
    private volatile boolean degraded;
    private final Map<UUID, Instant> lastSafetySweep = new ConcurrentHashMap<>();
    private final Map<UUID, SweepCursor> safetyCursors = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> sweepStartedAt = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> deliveryCursors = new ConcurrentHashMap<>();

    /**
     * 构造SLA组件并保存调用依赖；支持注入时钟以控制时间边界。
     */
    public TicketSlaScanner(SlaPersistenceMapper repository, SlaRebuildLeaseService leaseRepository,
                            TicketSlaService slaService, SlaDueIndex index,
                            TicketSlaProperties properties, Clock clock) {
        this.repository = repository;
        this.leaseRepository = leaseRepository;
        this.slaService = slaService;
        this.index = index;
        this.properties = properties;
        this.clock = clock;
    }

    /** 逐租户分批合并 Redis 候选与 MySQL 安全扫描，并独立补发已触发但尚未投递的通知。 */
    public ScanResult scanOnce() {
        if (com.flowdesk.common.tenant.TenantContextHolder.currentOrNull() != null) {
            throw new IllegalStateException("SLA scanner must start without a TenantContext");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Mutable result = new Mutable();
        boolean redisHealthy;
        try {
            redisHealthy = index.available();
        }
        catch (RuntimeException unavailable) {
            redisHealthy = false;
        }
        degraded = !redisHealthy;
        UUID tenantCursor = null;
        while (true) {
            final List<UUID> tenants;
            try {
                tenants = findTenantIdsWithSlaClocks(tenantCursor,
                        properties.getTenantBatchSize());
            }
            catch (RuntimeException discoveryFailure) {
                degraded = true;
                result.errors++;
                LOG.warn("SLA tenant discovery failed; retaining cursors for the next pass");
                break;
            }
            if (tenants.isEmpty()) break;
            result.tenantsDiscovered += tenants.size();
            for (UUID tenantId : tenants) {
                TenantContext context = TenantContext.ofPersistedTask(tenantId,
                        ActorRef.service("flowdesk-ticket-sla-scheduler"), UUID.randomUUID());
                try (TenantContextScope ignored = TenantContextScope.open(context)) {
                // 超时时钟可能已终结但通知尚未投递；使用独立游标遍历待通知触发，避免其被时钟扫描遗漏。
                var pending = findPendingSlaNotifications(tenantId, deliveryCursors.get(tenantId),
                        properties.getBatchSize());
                for (var delivery : pending) {
                    try {
                        slaService.retrySlaNotification(tenantId, delivery.ticketId(), delivery.clockId(), delivery.stage(), now);
                    } catch (RuntimeException failure) {
                        result.errors++;
                        LOG.warn("SLA notification delivery remains pending for a later pass");
                    }
                    deliveryCursors.put(tenantId, delivery.triggerId());
                }
                if (pending.size() < properties.getBatchSize()) deliveryCursors.remove(tenantId);
                if (SlaIndexRebuilder.isTenantRebuilding(tenantId)
                        || leaseRepository.isSlaRebuildActive(tenantId, now)) {
                    result.paused++;
                    continue;
                }
                Map<String, SlaDueIndex.Candidate> candidates = new LinkedHashMap<>();
                if (redisHealthy) {
                    try {
                        for (SlaDueIndex.Candidate candidate : index.due(tenantId, now,
                                properties.getBatchSize())) {
                            if (tenantId.equals(candidate.tenantId())) {
                                candidates.put(candidateKey(candidate.slaClockId(), candidate.stage()), candidate);
                            }
                        }
                    }
                    catch (RuntimeException unavailable) {
                        degraded = true;
                        redisHealthy = false;
                        LOG.warn("SLA Redis index unavailable; using bounded MySQL safety sweep");
                    }
                }

                // MySQL 安全扫描以有界排序键分页遍历所有 RUNNING 时钟。
                // 跨轮询保留游标，避免大租户永远只能检查第一页。
                SweepPage sweep = safetyPage(tenantId, now, redisHealthy);
                List<com.flowdesk.ticket.model.sla.SlaCandidateRow> rows = sweep.rows();
                if (sweep.backlog()) {
                    Instant started = sweepStartedAt.get(tenantId);
                    if (started != null && !now.isBefore(started.plus(properties.getSafetySweepInterval()))) {
                        degraded = true;
                    }
                }
                for (com.flowdesk.ticket.model.sla.SlaCandidateRow row : rows) {
                    if (row.reminderDueAt() != null
                            && !now.isBefore(row.reminderDueAt())
                            && !now.isAfter(row.deadlineAt())) {
                        candidates.put(candidateKey(row.slaClockId(), "REMINDER"),
                                new SlaDueIndex.Candidate(tenantId, row.slaClockId(), "REMINDER",
                                        row.reminderDueAt().toEpochMilli()));
                    }
                    if (now.isAfter(row.deadlineAt())) {
                        candidates.put(candidateKey(row.slaClockId(), "BREACH"),
                                new SlaDueIndex.Candidate(tenantId, row.slaClockId(), "BREACH",
                                        row.deadlineAt().toEpochMilli()));
                    }
                }

                for (SlaDueIndex.Candidate candidate : candidates.values()) {
                    com.flowdesk.ticket.model.sla.SlaCandidateRow row = rows.stream()
                            .filter(value -> value.slaClockId().equals(candidate.slaClockId()))
                            .findFirst().orElse(null);
                    UUID ticketId = row == null ? findTicketId(tenantId, candidate.slaClockId()) : row.ticketId();
                    if (ticketId == null) {
                        removeQuietly(tenantId, candidate.slaClockId(), candidate.stage());
                        continue;
                    }
                    result.candidates++;
                    com.flowdesk.ticket.model.sla.SlaProcessResult processed = slaService.processSlaCandidate(
                            tenantId, ticketId, candidate.slaClockId(), candidate.stage(), now);
                    if (processed.triggered()) result.triggered++;
                    syncClockIndex(tenantId, candidate.slaClockId(), now);
                }
                result.tenantsProcessed++;
                }
                catch (RuntimeException failure) {
                    result.errors++;
                    // 异常可能含基础设施信息，此处不记录原始文本。
                    LOG.warn("SLA tenant scan failed; tenant remains eligible for the next bounded sweep");
                }
            }
            tenantCursor = tenants.get(tenants.size() - 1);
        }
        return result.freeze(degraded);
    }

    /** 跨轮询保存排序键游标，直至完整走完该租户的所有运行时钟。 */
    private SweepPage safetyPage(UUID tenantId, Instant now, boolean redisHealthy) {
        SweepCursor cursor = safetyCursors.get(tenantId);
        boolean due = !redisHealthy || cursor != null || safetySweepDue(tenantId, now);
        if (!due) return new SweepPage(List.of(), false);
        List<com.flowdesk.ticket.model.sla.SlaCandidateRow> rows = findRunningSlaCandidates(
                tenantId, cursor == null ? null : cursor.updatedAt(),
                cursor == null ? null : cursor.clockId(), properties.getBatchSize());
        if (rows.isEmpty()) {
            safetyCursors.remove(tenantId);
            sweepStartedAt.remove(tenantId);
            lastSafetySweep.put(tenantId, now);
            return new SweepPage(List.of(), false);
        }
        if (cursor == null) sweepStartedAt.putIfAbsent(tenantId, now);
        com.flowdesk.ticket.model.sla.SlaCandidateRow last = rows.get(rows.size() - 1);
        safetyCursors.put(tenantId, new SweepCursor(last.updatedAt(), last.slaClockId()));
        return new SweepPage(rows, true);
    }

    /**
     * 从持久化时钟定位工单，供扫描器进入对应工单事务。
     */
    private UUID findTicketId(UUID tenantId, UUID clockId) {
        return findSlaClockById(tenantId, clockId).map(com.flowdesk.ticket.model.sla.SlaClockRow::ticketId)
                .orElse(null);
    }

    /**
     * 判断租户是否到了数据库安全扫描时间，控制补偿频率。
     */
    private boolean safetySweepDue(UUID tenantId, Instant now) {
        Instant previous = lastSafetySweep.get(tenantId);
        return previous == null || properties.getSafetySweepInterval() == null
                || !now.isBefore(previous.plus(properties.getSafetySweepInterval()));
    }

    /**
     * 按时钟与触发现状同步索引，移除陈旧候选并补齐有效阶段。
     */
    private void syncClockIndex(UUID tenantId, UUID clockId, Instant observedAt) {
        if (!index.available()) {
            degraded = true;
            return;
        }
        com.flowdesk.ticket.model.sla.SlaClockRow clock = findSlaClockById(tenantId, clockId).orElse(null);
        if (clock == null || !"RUNNING".equals(clock.status())) {
            removeQuietly(tenantId, clockId, "REMINDER");
            removeQuietly(tenantId, clockId, "BREACH");
            return;
        }
        boolean reminderExists = findSlaTrigger(tenantId, clockId, "REMINDER", false).isPresent();
        boolean breachExists = findSlaTrigger(tenantId, clockId, "BREACH", false).isPresent();
        // 提醒窗口关闭后不再重试提醒；已记为错过的 REMINDER 候选不能再次放回索引。
        if (clock.reminderDueAt() != null && !reminderExists
                && !observedAt.isAfter(clock.deadlineAt())) {
            index.upsert(tenantId, clockId, "REMINDER", clock.reminderDueAt());
        }
        else removeQuietly(tenantId, clockId, "REMINDER");
        if (!breachExists) index.upsert(tenantId, clockId, "BREACH", clock.deadlineAt());
        else removeQuietly(tenantId, clockId, "BREACH");
    }

    /**
     * 尝试移除陈旧候选，索引异常不覆盖已完成数据库业务结果。
     */
    private void removeQuietly(UUID tenantId, UUID clockId, String stage) {
        try { index.remove(tenantId, clockId, stage); }
        catch (RuntimeException unavailable) { degraded = true; }
    }

    /**
     * 组合时钟与阶段形成去重键，避免单轮重复处理。
     */
    private static String candidateKey(UUID clockId, String stage) {
        return clockId + ":" + stage;
    }

    /**
     * 判断当前索引状态是否要求启用数据库降级扫描。
     */
    public boolean isDegraded() { return degraded; }

    /**
     * 一次 SLA 扫描统计，保留候选、触发及降级处理数量。
     */
    public record ScanResult(int tenantsDiscovered, int tenantsProcessed, int candidates,
                             int triggered, int paused, int errors, boolean degraded) { }

    /**
     * 本轮处理的内部可变计数器，结束后转换为不可变统计结果。
     */
    private static final class Mutable {
        private int tenantsDiscovered;
        private int tenantsProcessed;
        private int candidates;
        private int triggered;
        private int paused;
        private int errors;
        /**
         * 把本轮可变计数转为不可变统计，供调用方观察处理进度。
         */
        private ScanResult freeze(boolean degraded) {
            return new ScanResult(tenantsDiscovered, tenantsProcessed, candidates,
                    triggered, paused, errors, degraded);
        }
    }

    /**
     * 数据库安全扫描的稳定分页位置，避免同时间记录遗漏。
     */
    private record SweepCursor(Instant updatedAt, UUID clockId) { }
    /**
     * 安全扫描当前页与后续游标，控制补偿处理范围。
     */
    private record SweepPage(List<com.flowdesk.ticket.model.sla.SlaCandidateRow> rows, boolean backlog) { }

    /**
     * 按当前租户和标识读取 SLA 时钟，无匹配时返回空结果。
     */
    private Optional<SlaClockRow> findSlaClockById(UUID tenantId, UUID slaClockId) {
        requireTenant(tenantId);
        return repository.findSlaClockByIdSelect(Uuids.toBytes(tenantId), Uuids.toBytes(slaClockId))
                .stream().findFirst();
    }

    /**
     * 分页读取运行中 SLA 候选，供索引重建或降级扫描。
     * 传入租户必须与当前上下文一致。
     */
    private List<SlaCandidateRow> findRunningSlaCandidates(UUID tenantId,
                                                           Instant cursorUpdatedAt,
                                                           UUID cursorClockId, int limit) {
        requireTenant(tenantId);
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("Invalid SLA candidate limit");
        return repository.findRunningSlaCandidatesSelect(Uuids.toBytes(tenantId), cursorUpdatedAt, cursorClockId, limit);
    }

    /**
     * 按游标读取存在时钟的租户，为重建与安全扫描提供有界范围。
     */
    private List<UUID> findTenantIdsWithSlaClocks(int limit) {
        return findTenantIdsWithSlaClocks(null, limit);
    }

    /**
     * 按游标读取存在时钟的租户，为重建与安全扫描提供有界范围。
     */
    private List<UUID> findTenantIdsWithSlaClocks(UUID afterTenantId, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("tenant limit must be between 1 and 1000");
        return repository.findTenantIdsWithSlaClocksSelect(afterTenantId, limit);
    }

    /**
     * 读取已触发但需补发通知的事实，供后台继续处理。
     * 传入租户必须与当前上下文一致。
     */
    private List<PendingSlaNotification> findPendingSlaNotifications(UUID tenantId, UUID afterTriggerId, int limit) {
        requireTenant(tenantId);
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("Invalid SLA retry batch size");
        return repository.findPendingSlaNotificationsSelect(Uuids.toBytes(tenantId), afterTriggerId, limit);
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
        return repository.findSlaTriggerSelect(Uuids.toBytes(tenantId), Uuids.toBytes(slaClockId), stage, lock).stream().findFirst();
    }
}
