package com.flowdesk.ticket.sla;

import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.ticket.repository.TicketRepository;
import com.flowdesk.ticket.service.TicketService;
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
 * Bounded, tenant-isolated SLA scanner.  Redis candidates are an optimization;
 * every item is re-read and conditionally decided in TicketService/MySQL.
 */
public final class TicketSlaScanner {
    private static final Logger LOG = LoggerFactory.getLogger(TicketSlaScanner.class);
    private final TicketRepository repository;
    private final TicketService ticketService;
    private final SlaDueIndex index;
    private final TicketSlaProperties properties;
    private final Clock clock;
    private volatile boolean degraded;
    private final Map<UUID, Instant> lastSafetySweep = new ConcurrentHashMap<>();
    private final Map<UUID, SweepCursor> safetyCursors = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> sweepStartedAt = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> deliveryCursors = new ConcurrentHashMap<>();

    public TicketSlaScanner(TicketRepository repository, TicketService ticketService,
                            SlaDueIndex index, TicketSlaProperties properties, Clock clock) {
        this.repository = repository;
        this.ticketService = ticketService;
        this.index = index;
        this.properties = properties;
        this.clock = clock;
    }

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
                tenants = repository.findTenantIdsWithSlaClocks(tenantCursor,
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
                // A breach may already be terminal while its notification is pending.
                // Walk every pending trigger with a separate cursor to avoid starvation.
                var pending = repository.findPendingSlaNotifications(tenantId, deliveryCursors.get(tenantId),
                        properties.getBatchSize());
                for (var delivery : pending) {
                    try {
                        ticketService.retrySlaNotification(tenantId, delivery.ticketId(), delivery.clockId(), delivery.stage(), now);
                    } catch (RuntimeException failure) {
                        result.errors++;
                        LOG.warn("SLA notification delivery remains pending for a later pass");
                    }
                    deliveryCursors.put(tenantId, delivery.triggerId());
                }
                if (pending.size() < properties.getBatchSize()) deliveryCursors.remove(tenantId);
                if (SlaIndexRebuilder.isTenantRebuilding(tenantId)
                        || repository.isSlaRebuildActive(tenantId, now)) {
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

                // The MySQL safety sweep is a complete bounded keyset walk over
                // RUNNING clocks.  A cursor is retained between polls, so a
                // large tenant cannot be silently reduced to the first page.
                SweepPage sweep = safetyPage(tenantId, now, redisHealthy);
                List<TicketRepository.SlaCandidateRow> rows = sweep.rows();
                if (sweep.backlog()) {
                    Instant started = sweepStartedAt.get(tenantId);
                    if (started != null && !now.isBefore(started.plus(properties.getSafetySweepInterval()))) {
                        degraded = true;
                    }
                }
                for (TicketRepository.SlaCandidateRow row : rows) {
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
                    TicketRepository.SlaCandidateRow row = rows.stream()
                            .filter(value -> value.slaClockId().equals(candidate.slaClockId()))
                            .findFirst().orElse(null);
                    UUID ticketId = row == null ? findTicketId(tenantId, candidate.slaClockId()) : row.ticketId();
                    if (ticketId == null) {
                        removeQuietly(tenantId, candidate.slaClockId(), candidate.stage());
                        continue;
                    }
                    result.candidates++;
                    TicketService.SlaProcessResult processed = ticketService.processSlaCandidate(
                            tenantId, ticketId, candidate.slaClockId(), candidate.stage(), now);
                    if (processed.triggered()) result.triggered++;
                    syncClockIndex(tenantId, candidate.slaClockId(), now);
                }
                result.tenantsProcessed++;
                }
                catch (RuntimeException failure) {
                    result.errors++;
                    // Do not leak exception text (it may contain infrastructure details).
                    LOG.warn("SLA tenant scan failed; tenant remains eligible for the next bounded sweep");
                }
            }
            tenantCursor = tenants.get(tenants.size() - 1);
        }
        return result.freeze(degraded);
    }

    private SweepPage safetyPage(UUID tenantId, Instant now, boolean redisHealthy) {
        SweepCursor cursor = safetyCursors.get(tenantId);
        boolean due = !redisHealthy || cursor != null || safetySweepDue(tenantId, now);
        if (!due) return new SweepPage(List.of(), false);
        List<TicketRepository.SlaCandidateRow> rows = repository.findRunningSlaCandidates(
                tenantId, cursor == null ? null : cursor.updatedAt(),
                cursor == null ? null : cursor.clockId(), properties.getBatchSize());
        if (rows.isEmpty()) {
            safetyCursors.remove(tenantId);
            sweepStartedAt.remove(tenantId);
            lastSafetySweep.put(tenantId, now);
            return new SweepPage(List.of(), false);
        }
        if (cursor == null) sweepStartedAt.putIfAbsent(tenantId, now);
        TicketRepository.SlaCandidateRow last = rows.get(rows.size() - 1);
        safetyCursors.put(tenantId, new SweepCursor(last.updatedAt(), last.slaClockId()));
        return new SweepPage(rows, true);
    }

    private UUID findTicketId(UUID tenantId, UUID clockId) {
        return repository.findSlaClockById(tenantId, clockId).map(TicketRepository.SlaClockRow::ticketId)
                .orElse(null);
    }

    private boolean safetySweepDue(UUID tenantId, Instant now) {
        Instant previous = lastSafetySweep.get(tenantId);
        return previous == null || properties.getSafetySweepInterval() == null
                || !now.isBefore(previous.plus(properties.getSafetySweepInterval()));
    }

    private void syncClockIndex(UUID tenantId, UUID clockId, Instant observedAt) {
        if (!index.available()) {
            degraded = true;
            return;
        }
        TicketRepository.SlaClockRow clock = repository.findSlaClockById(tenantId, clockId).orElse(null);
        if (clock == null || !"RUNNING".equals(clock.status())) {
            removeQuietly(tenantId, clockId, "REMINDER");
            removeQuietly(tenantId, clockId, "BREACH");
            return;
        }
        boolean reminderExists = repository.findSlaTrigger(tenantId, clockId, "REMINDER", false).isPresent();
        boolean breachExists = repository.findSlaTrigger(tenantId, clockId, "BREACH", false).isPresent();
        // A reminder is never retried after its deadline window closes.  The
        // previous behavior reinserted the overdue REMINDER member after
        // processSlaCandidate had deliberately recorded it as missed.
        if (clock.reminderDueAt() != null && !reminderExists
                && !observedAt.isAfter(clock.deadlineAt())) {
            index.upsert(tenantId, clockId, "REMINDER", clock.reminderDueAt());
        }
        else removeQuietly(tenantId, clockId, "REMINDER");
        if (!breachExists) index.upsert(tenantId, clockId, "BREACH", clock.deadlineAt());
        else removeQuietly(tenantId, clockId, "BREACH");
    }

    private void removeQuietly(UUID tenantId, UUID clockId, String stage) {
        try { index.remove(tenantId, clockId, stage); }
        catch (RuntimeException unavailable) { degraded = true; }
    }

    private static String candidateKey(UUID clockId, String stage) {
        return clockId + ":" + stage;
    }

    public boolean isDegraded() { return degraded; }

    public record ScanResult(int tenantsDiscovered, int tenantsProcessed, int candidates,
                             int triggered, int paused, int errors, boolean degraded) { }

    private static final class Mutable {
        private int tenantsDiscovered;
        private int tenantsProcessed;
        private int candidates;
        private int triggered;
        private int paused;
        private int errors;
        private ScanResult freeze(boolean degraded) {
            return new ScanResult(tenantsDiscovered, tenantsProcessed, candidates,
                    triggered, paused, errors, degraded);
        }
    }

    private record SweepCursor(Instant updatedAt, UUID clockId) { }
    private record SweepPage(List<TicketRepository.SlaCandidateRow> rows, boolean backlog) { }
}
