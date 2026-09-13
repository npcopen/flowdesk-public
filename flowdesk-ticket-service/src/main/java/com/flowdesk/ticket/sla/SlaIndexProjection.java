package com.flowdesk.ticket.sla;

import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.ticket.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Best-effort, post-commit projection of authoritative SLA clocks into the
 * Redis candidate index. Redis is intentionally never part of a Ticket write
 * transaction: a missed projection is recovered by the bounded MySQL sweep.
 */
public final class SlaIndexProjection {
    private static final Logger LOG = LoggerFactory.getLogger(SlaIndexProjection.class);

    private final TicketRepository repository;
    private final SlaDueIndex index;
    private final Clock clock;

    public SlaIndexProjection(TicketRepository repository, SlaDueIndex index, Clock clock) {
        this.repository = repository;
        this.index = index;
        this.clock = clock;
    }

    /**
     * Registers a read-after-commit projection of the final clock state. The
     * method is deliberately harmless when called more than once in one
     * transaction; each projection is derived from MySQL after commit.
     */
    public void schedule(UUID tenantId, UUID slaClockId) {
        if (tenantId == null || slaClockId == null) {
            return;
        }
        Runnable work = () -> project(tenantId, slaClockId);
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    work.run();
                }
            });
            return;
        }
        // This fallback is only for narrow internal callers. Production Ticket
        // mutations always register while their local transaction is active.
        work.run();
    }

    private void project(UUID tenantId, UUID slaClockId) {
        try {
            TenantContext current = TenantContextHolder.currentOrNull();
            if (current != null) {
                current.requireTenant(tenantId);
                projectInTenant(tenantId, slaClockId);
                return;
            }
            TenantContext task = TenantContext.ofPersistedTask(tenantId,
                    ActorRef.service("flowdesk-ticket-sla-projection"), UUID.randomUUID());
            try (TenantContextScope ignored = TenantContextScope.open(task)) {
                projectInTenant(tenantId, slaClockId);
            }
        }
        catch (RuntimeException unavailable) {
            // The scanner's MySQL safety sweep is the recovery mechanism. Do
            // not expose infrastructure detail or make a committed Ticket
            // mutation fail after its database commit.
            LOG.warn("SLA Redis projection deferred; MySQL safety sweep will reconcile it");
        }
    }

    private void projectInTenant(UUID tenantId, UUID slaClockId) {
        Instant observedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (SlaIndexRebuilder.isTenantRebuilding(tenantId)
                || repository.isSlaRebuildActive(tenantId, observedAt)) {
            return;
        }
        if (!index.available()) {
            return;
        }
        TicketRepository.SlaClockRow clockRow = repository.findSlaClockById(tenantId, slaClockId)
                .orElse(null);
        if (clockRow == null || !"RUNNING".equals(clockRow.status())) {
            remove(tenantId, slaClockId, "REMINDER");
            remove(tenantId, slaClockId, "BREACH");
            return;
        }

        boolean reminderTriggered = repository.findSlaTrigger(tenantId, slaClockId,
                "REMINDER", false).isPresent();
        if (clockRow.reminderDueAt() != null && !reminderTriggered
                && !observedAt.isAfter(clockRow.deadlineAt())) {
            upsert(tenantId, slaClockId, "REMINDER", clockRow.reminderDueAt());
        }
        else {
            remove(tenantId, slaClockId, "REMINDER");
        }

        boolean breachTriggered = repository.findSlaTrigger(tenantId, slaClockId,
                "BREACH", false).isPresent();
        if (!breachTriggered) {
            upsert(tenantId, slaClockId, "BREACH", clockRow.deadlineAt());
        }
        else {
            remove(tenantId, slaClockId, "BREACH");
        }
    }

    private void upsert(UUID tenantId, UUID slaClockId, String stage, Instant dueAt) {
        try {
            index.upsert(tenantId, slaClockId, stage, dueAt);
        }
        catch (RuntimeException unavailable) {
            LOG.warn("SLA Redis projection write deferred; MySQL safety sweep will reconcile it");
        }
    }

    private void remove(UUID tenantId, UUID slaClockId, String stage) {
        try {
            index.remove(tenantId, slaClockId, stage);
        }
        catch (RuntimeException unavailable) {
            LOG.warn("SLA Redis projection removal deferred; MySQL safety sweep will reconcile it");
        }
    }
}
