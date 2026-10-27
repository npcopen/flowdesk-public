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
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.tenant.TenantContextScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在 MySQL 租约与递增栅栏版本保护下重建 Redis SLA 候选索引。
 * 租约、高水位和预期成员摘要保存在工单数据库，替换前再次校验以拒绝过期工作线程。
 */
public final class SlaIndexRebuilder {
    private static final Logger LOG = LoggerFactory.getLogger(SlaIndexRebuilder.class);
    private static final Set<UUID> REBUILDING = ConcurrentHashMap.newKeySet();

    private final SlaPersistenceMapper repository;
    private final SlaRebuildLeaseService leaseRepository;
    private final SlaDueIndex index;
    private final TicketSlaProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    /**
     * 构造SLA组件并保存调用依赖；支持注入时钟以控制时间边界。
     */
    public SlaIndexRebuilder(SlaPersistenceMapper repository, SlaRebuildLeaseService leaseRepository,
                             SlaDueIndex index, TicketSlaProperties properties,
                             PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.leaseRepository = leaseRepository;
        this.index = index;
        this.properties = properties;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** 保留进程内兼容判断；扫描器同时检查数据库中的持久租约。 */
    public static boolean isTenantRebuilding(UUID tenantId) {
        return REBUILDING.contains(tenantId);
    }

    /**
     * 领取有界重建任务，从数据库重建并校验后切换索引。
     */
    public RebuildResult rebuildOnce() {
        if (!(index instanceof RedisSlaDueIndex)) {
            return new RebuildResult(0, 0, 0, 0, true, "REDIS_INDEX_DISABLED");
        }
        int discovered = 0;
        int completed = 0;
        int failed = 0;
        int members = 0;
        UUID tenantCursor = null;
        while (true) {
            final List<UUID> tenants;
            try {
                tenants = findTenantIdsWithSlaClocks(tenantCursor,
                        properties.getTenantBatchSize());
            }
            catch (RuntimeException discoveryFailure) {
                LOG.warn("SLA rebuild tenant discovery failed; retaining the formal index");
                failed++;
                break;
            }
            if (tenants.isEmpty()) break;
            discovered += tenants.size();
            for (UUID tenantId : tenants) {
                TenantContext context = TenantContext.ofPersistedTask(tenantId,
                        ActorRef.service("flowdesk-ticket-sla-rebuilder"), UUID.randomUUID());
                try (TenantContextScope ignored = TenantContextScope.open(context)) {
                    RebuildResult one = rebuildTenant(tenantId);
                    if (one.failed() || one.degraded()) failed++; else completed++;
                    members += one.membersWritten();
                }
                catch (RuntimeException failure) {
                    failed++;
                    LOG.warn("SLA index rebuild failed for a tenant; temporary key is discarded");
                }
            }
            tenantCursor = tenants.get(tenants.size() - 1);
        }
        return new RebuildResult(discovered, completed, failed, members,
                failed > 0, failed > 0 ? "REBUILD_PARTIAL_FAILURE" : "REBUILD_OK");
    }

    /** 领取租户重建租约后写临时索引，追平数据库增量并核对摘要，再带栅栏原子切换正式索引。 */
    public RebuildResult rebuildTenant(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("SLA rebuild tenantId is required");
        }
        TenantContextHolder.current().requireTenant(tenantId);
        if (!(index instanceof RedisSlaDueIndex redis)) {
            return new RebuildResult(1, 0, 0, 0, true, "REDIS_INDEX_DISABLED");
        }
        UUID runId = UUID.randomUUID();
        Instant capturedAt = now();
        com.flowdesk.ticket.model.sla.SlaHighWaterRow highWater = findSlaHighWater(tenantId).orElse(null);
        Instant highAt = highWater == null ? null : highWater.updatedAt();
        UUID highClock = highWater == null ? null : highWater.slaClockId();
        Lease lease = claim(tenantId, runId, highAt, highClock, capturedAt);
        if (lease == null) {
            return new RebuildResult(1, 0, 0, 0, false, "REBUILD_LEASE_BUSY");
        }

        REBUILDING.add(tenantId);
        boolean swapped = false;
        TreeMap<String, Long> expected = new TreeMap<>();
        try {
            // 以数据库 epoch 为准，通过单调比较更新安装 Redis 栅栏，防止延迟的旧线程覆盖新线程。
            if (!redis.installFence(tenantId, lease.runId(), lease.epoch(),
                    properties.getLeaseDuration())) {
                throw new LeaseLostException();
            }
            if (!redis.ensureRebuildKey(lease.runId(), tenantId, properties.getLeaseDuration())) {
                throw new LeaseLostException();
            }
            renewOrThrow(redis, tenantId, lease);
            scanInitial(redis, tenantId, runId, expected, lease);

            transition(tenantId, lease, "CATCHING_UP");
            Tuple cursor = catchUp(redis, tenantId, runId, expected,
                    new Tuple(highAt, highClock), lease, false, highAt != null);

            // 进入 SWAPPING 后再次有界读取尾部变更；发现并发时钟变化就回到 CATCHING_UP，避免发布过期集合。
            while (true) {
                transition(tenantId, lease, "SWAPPING");
                renewOrThrow(redis, tenantId, lease);
                List<com.flowdesk.ticket.model.sla.SlaCandidateRow> tail = findSlaChangesAfter(
                        tenantId, cursor.at(), cursor.id(), 1);
                if (!tail.isEmpty()) {
                    transition(tenantId, lease, "CATCHING_UP");
                    cursor = catchUp(redis, tenantId, runId, expected, cursor, lease, false, false);
                    continue;
                }
                com.flowdesk.ticket.model.sla.SlaHighWaterRow current = findSlaHighWater(tenantId).orElse(null);
                if (after(current, cursor)) {
                    transition(tenantId, lease, "CATCHING_UP");
                    cursor = catchUp(redis, tenantId, runId, expected, cursor, lease, false, false);
                    continue;
                }
                renewOrThrow(redis, tenantId, lease);
                break;
            }
            String expectedDigest = RedisSlaDueIndex.digest(expected);
            RedisSlaDueIndex.RebuildSummary actual = redis.rebuildSummary(runId, tenantId);
            if (actual.count() != expected.size() || !expectedDigest.equals(actual.digest())) {
                throw new IllegalStateException("SLA rebuild summary mismatch before swap");
            }
            // 紧贴 Redis 原子操作再次校验 epoch 与到期时间，防止已被替换的工作线程发布临时键。
            renewOrThrow(redis, tenantId, lease);
            redis.swapRebuild(runId, tenantId, expectedDigest, expected.size(), lease.epoch());
            swapped = true;

            // 数据库可能在替换前的尾部读取之后提交变更，因此解除扫描暂停前须在同一栅栏下修补正式索引。
            // 更晚的变更由正常 MySQL 安全扫描补齐，数据库始终是权威来源。
            transition(tenantId, lease, "CATCHING_UP");
            while (true) {
                cursor = catchUp(redis, tenantId, runId, expected, cursor, lease, true, false);
                renewOrThrow(redis, tenantId, lease, false);
                List<com.flowdesk.ticket.model.sla.SlaCandidateRow> tail = findSlaChangesAfter(
                        tenantId, cursor.at(), cursor.id(), 1);
                if (!tail.isEmpty()) {
                    continue;
                }
                com.flowdesk.ticket.model.sla.SlaHighWaterRow current = findSlaHighWater(tenantId).orElse(null);
                if (after(current, cursor)) {
                    continue;
                }
                RedisSlaDueIndex.RebuildSummary formal = redis.formalSummary(tenantId);
                String formalDigest = RedisSlaDueIndex.digest(expected);
                if (formal.count() != expected.size() || !formalDigest.equals(formal.digest())) {
                    throw new IllegalStateException("SLA rebuild summary mismatch after swap");
                }
                // 摘要校验后再检查一次尾部；这不能使 Redis 与 MySQL 形成事务，但可缩小替换窗口并拒绝旧线程写入。
                if (findSlaChangesAfter(tenantId, cursor.at(), cursor.id(), 1).isEmpty()) {
                    break;
                }
            }
            release(tenantId, lease, "IDLE", null);
            return new RebuildResult(1, 1, 0, expected.size(), false, "REBUILD_OK");
        }
        catch (LeaseLostException lost) {
            if (!swapped) redis.discardRebuild(runId, tenantId);
            LOG.warn("SLA rebuild lease lost; formal key was left untouched");
            return new RebuildResult(1, 0, 1, expected.size(), true, "REBUILD_LEASE_LOST");
        }
        catch (RuntimeException failure) {
            if (!swapped) redis.discardRebuild(runId, tenantId);
            try {
                release(tenantId, lease, "DEGRADED", "REBUILD_FAILED");
            }
            catch (RuntimeException ignored) {
                // 更新的 epoch 已持有租约，由其决定下一次索引替换。
            }
            return new RebuildResult(1, 0, 1, expected.size(), true, "REBUILD_FAILED");
        }
        finally {
            REBUILDING.remove(tenantId);
        }
    }

    /**
     * 按当前状态和版本领取处理权，返回本次租约快照。
     */
    private Lease claim(UUID tenantId, UUID runId, Instant highAt, UUID highClock, Instant now) {
        return transactions.execute(tx -> {
            leaseRepository.ensureSlaRebuildLease(tenantId, now);
            if (leaseRepository.claimSlaRebuildLease(tenantId, runId, highAt, highClock,
                    now.plus(properties.getLeaseDuration()), now) != 1) return null;
            com.flowdesk.ticket.model.sla.SlaRebuildLeaseRow row = leaseRepository.findSlaRebuildLeaseForUpdate(tenantId)
                    .orElseThrow(() -> new IllegalStateException("SLA rebuild lease disappeared"));
            return new Lease(runId, row.fencingEpoch());
        });
    }

    /**
     * 按稳定游标扫描运行中时钟，将初始快照写入临时索引。
     */
    private void scanInitial(RedisSlaDueIndex redis, UUID tenantId, UUID runId,
                             Map<String, Long> expected, Lease lease) {
        Instant cursorAt = null;
        UUID cursorId = null;
        while (true) {
            renewOrThrow(redis, tenantId, lease);
            List<com.flowdesk.ticket.model.sla.SlaCandidateRow> rows = findRunningSlaCandidates(
                    tenantId, cursorAt, cursorId, Math.min(properties.getBatchSize(), 500));
            if (rows.isEmpty()) return;
            for (com.flowdesk.ticket.model.sla.SlaCandidateRow row : rows) {
                syncCandidate(redis, runId, tenantId, row, expected, lease, false);
            }
            com.flowdesk.ticket.model.sla.SlaCandidateRow last = rows.get(rows.size() - 1);
            cursorAt = last.updatedAt();
            cursorId = last.slaClockId();
        }
    }

    /**
     * 依据高水位补齐重建期间的变化，缩小初始扫描与切换的一致性窗口。
     */
    private Tuple catchUp(RedisSlaDueIndex redis, UUID tenantId, UUID runId,
                          Map<String, Long> expected, Tuple cursor, Lease lease,
                          boolean formal, boolean inclusiveFirstPage) {
        Instant cursorAt = cursor.at();
        UUID cursorId = cursor.id();
        boolean firstPage = inclusiveFirstPage;
        while (true) {
            renewOrThrow(redis, tenantId, lease, formal);
            List<com.flowdesk.ticket.model.sla.SlaCandidateRow> changes;
            if (firstPage && cursorAt != null) {
                changes = findSlaChangesAtOrAfter(tenantId, cursorAt,
                        Math.min(properties.getBatchSize(), 500));
                firstPage = false;
            }
            else {
                changes = findSlaChangesAfter(tenantId, cursorAt, cursorId,
                        Math.min(properties.getBatchSize(), 500));
            }
            if (changes.isEmpty()) return new Tuple(cursorAt, cursorId);
            for (com.flowdesk.ticket.model.sla.SlaCandidateRow row : changes) {
                syncCandidate(redis, runId, tenantId, row, expected, lease, formal);
            }
            com.flowdesk.ticket.model.sla.SlaCandidateRow last = changes.get(changes.size() - 1);
            cursorAt = last.updatedAt();
            cursorId = last.slaClockId();
        }
    }

    /**
     * 根据当前时钟及触发事实添加或移除重建候选。
     */
    private void syncCandidate(RedisSlaDueIndex redis, UUID runId, UUID tenantId,
                               com.flowdesk.ticket.model.sla.SlaCandidateRow row,
                               Map<String, Long> expected, Lease lease,
                               boolean formal) {
        if (!"RUNNING".equals(row.status())) {
            remove(redis, runId, tenantId, row.slaClockId(), "REMINDER", expected, lease, formal);
            remove(redis, runId, tenantId, row.slaClockId(), "BREACH", expected, lease, formal);
            return;
        }
        String reminder = SlaDueIndexCodec.member(tenantId, row.slaClockId(), "REMINDER");
        if (row.reminderDueAt() != null
                && findSlaTrigger(tenantId, row.slaClockId(), "REMINDER", false).isEmpty()) {
            upsert(redis, runId, tenantId, row.slaClockId(), "REMINDER", row.reminderDueAt(), lease, formal);
            expected.put(reminder, SlaDueIndexCodec.score(row.reminderDueAt()));
        }
        else {
            remove(redis, runId, tenantId, row.slaClockId(), "REMINDER", expected, lease, formal);
            expected.remove(reminder);
        }
        String breach = SlaDueIndexCodec.member(tenantId, row.slaClockId(), "BREACH");
        if (findSlaTrigger(tenantId, row.slaClockId(), "BREACH", false).isEmpty()) {
            upsert(redis, runId, tenantId, row.slaClockId(), "BREACH", row.deadlineAt(), lease, formal);
            expected.put(breach, SlaDueIndexCodec.score(row.deadlineAt()));
        }
        else {
            remove(redis, runId, tenantId, row.slaClockId(), "BREACH", expected, lease, formal);
            expected.remove(breach);
        }
    }

    /**
     * 写入或刷新到期候选，使扫描器按时间定位 SLA 阶段。
     */
    private void upsert(RedisSlaDueIndex redis, UUID runId, UUID tenantId, UUID clockId,
                        String stage, Instant dueAt, Lease lease, boolean formal) {
        if (formal) {
            if (!redis.upsertFenced(tenantId, lease.runId(), lease.epoch(), clockId, stage, dueAt)) {
                throw new LeaseLostException();
            }
            return;
        }
        redis.upsertRebuild(runId, tenantId, clockId, stage, dueAt, properties.getLeaseDuration());
    }

    /**
     * 移除已失效候选，避免已结算或已触发阶段继续被扫描。
     */
    private void remove(RedisSlaDueIndex redis, UUID runId, UUID tenantId, UUID clockId,
                        String stage, Map<String, Long> expected, Lease lease,
                        boolean formal) {
        if (formal) {
            if (!redis.removeFenced(tenantId, lease.runId(), lease.epoch(), clockId, stage)) {
                throw new LeaseLostException();
            }
        }
        else {
            redis.removeRebuild(runId, tenantId, clockId, stage, properties.getLeaseDuration());
        }
        expected.remove(SlaDueIndexCodec.member(tenantId, clockId, stage));
    }

    /**
     * 按领取身份推进重建阶段，仅持有有效租约者可继续。
     */
    private void transition(UUID tenantId, Lease lease, String state) {
        Instant at = now();
        int changed = transactions.execute(tx -> leaseRepository.advanceSlaRebuildLease(
                tenantId, lease.runId(), lease.epoch(), state,
                at.plus(properties.getLeaseDuration()), null, at));
        if (changed != 1) throw new LeaseLostException();
    }

    /**
     * 续租并核验所有权，租约丢失时立即终止后续索引变更。
     */
    private void renewOrThrow(RedisSlaDueIndex redis, UUID tenantId, Lease lease) {
        renewOrThrow(redis, tenantId, lease, true);
    }

    /**
     * 续租并核验所有权，租约丢失时立即终止后续索引变更。
     */
    private void renewOrThrow(RedisSlaDueIndex redis, UUID tenantId, Lease lease,
                              boolean temporaryKeyRequired) {
        Instant at = now();
        int changed = transactions.execute(tx -> leaseRepository.renewSlaRebuildLease(
                tenantId, lease.runId(), lease.epoch(),
                at.plus(properties.getLeaseDuration()), at));
        if (changed != 1) throw new LeaseLostException();
        if (!redis.renewFence(tenantId, lease.runId(), lease.epoch(), properties.getLeaseDuration())
                || (temporaryKeyRequired
                && !redis.renewRebuildTtl(lease.runId(), tenantId, properties.getLeaseDuration()))) {
            throw new LeaseLostException();
        }
    }

    /**
     * 释放本次持有的重建租约，供其他工作实例继续维护。
     */
    private void release(UUID tenantId, Lease lease, String state, String code) {
        Instant at = now();
        int changed = transactions.execute(tx -> leaseRepository.releaseSlaRebuildLease(
                tenantId, lease.runId(), lease.epoch(), state, code, at));
        if (changed != 1 && "IDLE".equals(state)) throw new LeaseLostException();
    }

    /**
     * 取得当前时间并规范为业务使用的时间精度。
     */
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    /**
     * 比较时间与标识构成的游标，判断记录是否位于高水位之后。
     */
    private static boolean after(com.flowdesk.ticket.model.sla.SlaHighWaterRow value, Tuple cursor) {
        if (value == null) return false;
        if (cursor.at() == null || cursor.id() == null) return true;
        return value.updatedAt().isAfter(cursor.at())
                || (value.updatedAt().equals(cursor.at())
                && value.slaClockId().compareTo(cursor.id()) > 0);
    }

    /**
     * 本轮索引重建领取快照，限定租户、令牌与当前租约所有权。
     */
    private record Lease(UUID runId, long epoch) { }
    /**
     * 重建增量同步游标，以时间和唯一标识确定稳定顺序。
     */
    private record Tuple(Instant at, UUID id) { }
    /**
     * 索引重建租约丢失信号，要求旧所有者停止后续索引变更。
     */
    private static final class LeaseLostException extends RuntimeException { }

    /**
     * 一次 SLA 索引重建结果，保留完成情况与处理进度。
     */
    public record RebuildResult(int tenantsDiscovered, int tenantsCompleted, int tenantsFailed,
                                int membersWritten, boolean degraded, String code) {
        /**
         * 构造本轮重建失败统计，保留已处理进度供诊断和调度使用。
         */
        boolean failed() { return tenantsFailed > 0; }
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
     * 读取时钟变化高水位，标记重建增量同步边界。
     * 传入租户必须与当前上下文一致。
     */
    private Optional<SlaHighWaterRow> findSlaHighWater(UUID tenantId) {
        requireTenant(tenantId);
        return repository.findSlaHighWaterSelect(Uuids.toBytes(tenantId)).stream().findFirst();
    }

    /**
     * 读取高水位后的时钟变化，以时间与标识保证稳定分页。
     * 传入租户必须与当前上下文一致。
     */
    private List<SlaCandidateRow> findSlaChangesAfter(UUID tenantId, Instant updatedAt,
                                                     UUID clockId, int limit) {
        requireTenant(tenantId);
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("Invalid SLA change limit");
        return repository.findSlaChangesAfterSelect(Uuids.toBytes(tenantId), updatedAt, clockId, limit);
    }

    /**
     * 包含边界时间读取变化，补齐重建边界并发更新。
     * 传入租户必须与当前上下文一致。
     */
    private List<SlaCandidateRow> findSlaChangesAtOrAfter(UUID tenantId,
                                                         Instant updatedAt, int limit) {
        requireTenant(tenantId);
        if (updatedAt == null || limit < 1 || limit > 500) {
            throw new IllegalArgumentException("SLA inclusive change query is invalid");
        }
        return repository.findSlaChangesAtOrAfterSelect(Uuids.toBytes(tenantId), Timestamp.from(updatedAt), limit);
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
