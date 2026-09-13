package com.flowdesk.ticket.sla;

import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.ticket.repository.TicketRepository;
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
 * MySQL-fenced rebuild of the Redis SLA candidate index. The Redis key is
 * disposable: the lease/epoch, high-water tuple and expected member digest all
 * live in Ticket's database and fence stale workers before a swap.
 */
public final class SlaIndexRebuilder {
    private static final Logger LOG = LoggerFactory.getLogger(SlaIndexRebuilder.class);
    private static final Set<UUID> REBUILDING = ConcurrentHashMap.newKeySet();

    private final TicketRepository repository;
    private final SlaDueIndex index;
    private final TicketSlaProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public SlaIndexRebuilder(TicketRepository repository, SlaDueIndex index,
                             TicketSlaProperties properties,
                             PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.index = index;
        this.properties = properties;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Kept for compatibility; scanner also consults the persisted lease. */
    public static boolean isTenantRebuilding(UUID tenantId) {
        return REBUILDING.contains(tenantId);
    }

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
                tenants = repository.findTenantIdsWithSlaClocks(tenantCursor,
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
        TicketRepository.SlaHighWaterRow highWater = repository.findSlaHighWater(tenantId).orElse(null);
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
            // The database epoch is authoritative.  Install it in Redis with
            // a monotonic compare-and-set before any final swap; a delayed old
            // worker cannot overwrite a newer worker's fence.
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

            // Move to SWAPPING only after a final bounded keyset read.  A
            // concurrent Clock transition discovered in that window sends the
            // worker back through CATCHING_UP instead of publishing a stale set.
            while (true) {
                transition(tenantId, lease, "SWAPPING");
                renewOrThrow(redis, tenantId, lease);
                List<TicketRepository.SlaCandidateRow> tail = repository.findSlaChangesAfter(
                        tenantId, cursor.at(), cursor.id(), 1);
                if (!tail.isEmpty()) {
                    transition(tenantId, lease, "CATCHING_UP");
                    cursor = catchUp(redis, tenantId, runId, expected, cursor, lease, false, false);
                    continue;
                }
                TicketRepository.SlaHighWaterRow current = repository.findSlaHighWater(tenantId).orElse(null);
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
            // The epoch/expiry check is immediately before the atomic Redis
            // operation; a replaced worker cannot publish its temporary key.
            renewOrThrow(redis, tenantId, lease);
            redis.swapRebuild(runId, tenantId, expectedDigest, expected.size(), lease.epoch());
            swapped = true;

            // A database mutation may commit after the pre-swap tail read. The
            // formal key is therefore patched under the same Redis fence before
            // releasing the scanner pause.  Any later mutation is still safe:
            // MySQL remains authoritative and the normal safety sweep catches it.
            transition(tenantId, lease, "CATCHING_UP");
            while (true) {
                cursor = catchUp(redis, tenantId, runId, expected, cursor, lease, true, false);
                renewOrThrow(redis, tenantId, lease, false);
                List<TicketRepository.SlaCandidateRow> tail = repository.findSlaChangesAfter(
                        tenantId, cursor.at(), cursor.id(), 1);
                if (!tail.isEmpty()) {
                    continue;
                }
                TicketRepository.SlaHighWaterRow current = repository.findSlaHighWater(tenantId).orElse(null);
                if (after(current, cursor)) {
                    continue;
                }
                RedisSlaDueIndex.RebuildSummary formal = redis.formalSummary(tenantId);
                String formalDigest = RedisSlaDueIndex.digest(expected);
                if (formal.count() != expected.size() || !formalDigest.equals(formal.digest())) {
                    throw new IllegalStateException("SLA rebuild summary mismatch after swap");
                }
                // Check one final tail after the digest.  The comparison cannot
                // make Redis transactional with MySQL, but it bounds the swap
                // window without ever allowing a stale worker to write.
                if (repository.findSlaChangesAfter(tenantId, cursor.at(), cursor.id(), 1).isEmpty()) {
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
                // A newer epoch owns the lease; it will decide the next swap.
            }
            return new RebuildResult(1, 0, 1, expected.size(), true, "REBUILD_FAILED");
        }
        finally {
            REBUILDING.remove(tenantId);
        }
    }

    private Lease claim(UUID tenantId, UUID runId, Instant highAt, UUID highClock, Instant now) {
        return transactions.execute(tx -> {
            repository.ensureSlaRebuildLease(tenantId, now);
            if (repository.claimSlaRebuildLease(tenantId, runId, highAt, highClock,
                    now.plus(properties.getLeaseDuration()), now) != 1) return null;
            TicketRepository.SlaRebuildLeaseRow row = repository.findSlaRebuildLeaseForUpdate(tenantId)
                    .orElseThrow(() -> new IllegalStateException("SLA rebuild lease disappeared"));
            return new Lease(runId, row.fencingEpoch());
        });
    }

    private void scanInitial(RedisSlaDueIndex redis, UUID tenantId, UUID runId,
                             Map<String, Long> expected, Lease lease) {
        Instant cursorAt = null;
        UUID cursorId = null;
        while (true) {
            renewOrThrow(redis, tenantId, lease);
            List<TicketRepository.SlaCandidateRow> rows = repository.findRunningSlaCandidates(
                    tenantId, cursorAt, cursorId, Math.min(properties.getBatchSize(), 500));
            if (rows.isEmpty()) return;
            for (TicketRepository.SlaCandidateRow row : rows) {
                syncCandidate(redis, runId, tenantId, row, expected, lease, false);
            }
            TicketRepository.SlaCandidateRow last = rows.get(rows.size() - 1);
            cursorAt = last.updatedAt();
            cursorId = last.slaClockId();
        }
    }

    private Tuple catchUp(RedisSlaDueIndex redis, UUID tenantId, UUID runId,
                          Map<String, Long> expected, Tuple cursor, Lease lease,
                          boolean formal, boolean inclusiveFirstPage) {
        Instant cursorAt = cursor.at();
        UUID cursorId = cursor.id();
        boolean firstPage = inclusiveFirstPage;
        while (true) {
            renewOrThrow(redis, tenantId, lease, formal);
            List<TicketRepository.SlaCandidateRow> changes;
            if (firstPage && cursorAt != null) {
                changes = repository.findSlaChangesAtOrAfter(tenantId, cursorAt,
                        Math.min(properties.getBatchSize(), 500));
                firstPage = false;
            }
            else {
                changes = repository.findSlaChangesAfter(tenantId, cursorAt, cursorId,
                        Math.min(properties.getBatchSize(), 500));
            }
            if (changes.isEmpty()) return new Tuple(cursorAt, cursorId);
            for (TicketRepository.SlaCandidateRow row : changes) {
                syncCandidate(redis, runId, tenantId, row, expected, lease, formal);
            }
            TicketRepository.SlaCandidateRow last = changes.get(changes.size() - 1);
            cursorAt = last.updatedAt();
            cursorId = last.slaClockId();
        }
    }

    private void syncCandidate(RedisSlaDueIndex redis, UUID runId, UUID tenantId,
                               TicketRepository.SlaCandidateRow row,
                               Map<String, Long> expected, Lease lease,
                               boolean formal) {
        if (!"RUNNING".equals(row.status())) {
            remove(redis, runId, tenantId, row.slaClockId(), "REMINDER", expected, lease, formal);
            remove(redis, runId, tenantId, row.slaClockId(), "BREACH", expected, lease, formal);
            return;
        }
        String reminder = SlaDueIndexCodec.member(tenantId, row.slaClockId(), "REMINDER");
        if (row.reminderDueAt() != null
                && repository.findSlaTrigger(tenantId, row.slaClockId(), "REMINDER", false).isEmpty()) {
            upsert(redis, runId, tenantId, row.slaClockId(), "REMINDER", row.reminderDueAt(), lease, formal);
            expected.put(reminder, SlaDueIndexCodec.score(row.reminderDueAt()));
        }
        else {
            remove(redis, runId, tenantId, row.slaClockId(), "REMINDER", expected, lease, formal);
            expected.remove(reminder);
        }
        String breach = SlaDueIndexCodec.member(tenantId, row.slaClockId(), "BREACH");
        if (repository.findSlaTrigger(tenantId, row.slaClockId(), "BREACH", false).isEmpty()) {
            upsert(redis, runId, tenantId, row.slaClockId(), "BREACH", row.deadlineAt(), lease, formal);
            expected.put(breach, SlaDueIndexCodec.score(row.deadlineAt()));
        }
        else {
            remove(redis, runId, tenantId, row.slaClockId(), "BREACH", expected, lease, formal);
            expected.remove(breach);
        }
    }

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

    private void transition(UUID tenantId, Lease lease, String state) {
        Instant at = now();
        int changed = transactions.execute(tx -> repository.advanceSlaRebuildLease(
                tenantId, lease.runId(), lease.epoch(), state,
                at.plus(properties.getLeaseDuration()), null, at));
        if (changed != 1) throw new LeaseLostException();
    }

    private void renewOrThrow(RedisSlaDueIndex redis, UUID tenantId, Lease lease) {
        renewOrThrow(redis, tenantId, lease, true);
    }

    private void renewOrThrow(RedisSlaDueIndex redis, UUID tenantId, Lease lease,
                              boolean temporaryKeyRequired) {
        Instant at = now();
        int changed = transactions.execute(tx -> repository.renewSlaRebuildLease(
                tenantId, lease.runId(), lease.epoch(),
                at.plus(properties.getLeaseDuration()), at));
        if (changed != 1) throw new LeaseLostException();
        if (!redis.renewFence(tenantId, lease.runId(), lease.epoch(), properties.getLeaseDuration())
                || (temporaryKeyRequired
                && !redis.renewRebuildTtl(lease.runId(), tenantId, properties.getLeaseDuration()))) {
            throw new LeaseLostException();
        }
    }

    private void release(UUID tenantId, Lease lease, String state, String code) {
        Instant at = now();
        int changed = transactions.execute(tx -> repository.releaseSlaRebuildLease(
                tenantId, lease.runId(), lease.epoch(), state, code, at));
        if (changed != 1 && "IDLE".equals(state)) throw new LeaseLostException();
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    private static boolean after(TicketRepository.SlaHighWaterRow value, Tuple cursor) {
        if (value == null) return false;
        if (cursor.at() == null || cursor.id() == null) return true;
        return value.updatedAt().isAfter(cursor.at())
                || (value.updatedAt().equals(cursor.at())
                && value.slaClockId().compareTo(cursor.id()) > 0);
    }

    private record Lease(UUID runId, long epoch) { }
    private record Tuple(Instant at, UUID id) { }
    private static final class LeaseLostException extends RuntimeException { }

    public record RebuildResult(int tenantsDiscovered, int tenantsCompleted, int tenantsFailed,
                                int membersWritten, boolean degraded, String code) {
        boolean failed() { return tenantsFailed > 0; }
    }
}
