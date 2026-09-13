package com.flowdesk.ticket.sla;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Periodically refreshes Ticket-owned SLA target qualification snapshots. */
public final class SlaQualificationRefreshWorker implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(SlaQualificationRefreshWorker.class);

    private final SlaTargetQualificationService qualification;
    private final TicketSlaProperties properties;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ScheduledFuture<?> scheduled;

    public SlaQualificationRefreshWorker(SlaTargetQualificationService qualification,
                                         TicketSlaProperties properties,
                                         TaskScheduler scheduler, Clock clock) {
        this.qualification = qualification;
        this.properties = properties;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @Override
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            schedule(Duration.ZERO);
            LOG.info("Ticket SLA qualification refresh worker is ready");
        }
    }

    private void poll() {
        if (!running.get()) return;
        try {
            qualification.refreshAll();
        }
        catch (RuntimeException failure) {
            // A failed pass leaves the previous snapshot untouched; trigger
            // validation remains fail-closed until a later pass succeeds.
            LOG.warn("SLA qualification refresh failed; retrying on next interval");
        }
        schedule(properties.getQualificationRefreshInterval());
    }

    private synchronized void schedule(Duration delay) {
        if (running.get()) scheduled = scheduler.schedule(this::poll, clock.instant().plus(delay));
    }

    @Override
    public synchronized void stop() {
        running.set(false);
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
    }

    @Override public void stop(Runnable callback) { try { stop(); } finally { callback.run(); } }
    @Override public boolean isRunning() { return running.get(); }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 160; }
}
