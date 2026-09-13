package com.flowdesk.ticket.sla;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Separate opt-in rebuild loop so normal SLA scanning can remain bounded. */
public final class SlaRebuildPollingWorker implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(SlaRebuildPollingWorker.class);
    private final SlaIndexRebuilder rebuilder;
    private final TicketSlaProperties properties;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ScheduledFuture<?> scheduled;

    public SlaRebuildPollingWorker(SlaIndexRebuilder rebuilder, TicketSlaProperties properties,
                                   TaskScheduler scheduler, Clock clock) {
        this.rebuilder = rebuilder; this.properties = properties; this.scheduler = scheduler; this.clock = clock;
    }
    @Override public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            schedule(Duration.ZERO);
            LOG.info("Ticket SLA index rebuilder is ready");
        }
    }
    private void poll() {
        if (!running.get()) return;
        try { rebuilder.rebuildOnce(); } catch (RuntimeException ignored) { LOG.warn("SLA index rebuild poll failed"); }
        schedule(Duration.ofMinutes(5));
    }
    private synchronized void schedule(Duration delay) {
        if (running.get()) scheduled = scheduler.schedule(this::poll, clock.instant().plus(delay));
    }
    @Override public synchronized void stop() { running.set(false); if (scheduled != null) { scheduled.cancel(false); scheduled = null; } }
    @Override public void stop(Runnable callback) { try { stop(); } finally { callback.run(); } }
    @Override public boolean isRunning() { return running.get(); }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 140; }
}
