package com.flowdesk.ticket.sla;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opt-in bounded scheduler for SLA scans and (optionally) rebuilds. */
public final class TicketSlaPollingWorker implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(TicketSlaPollingWorker.class);
    private final TicketSlaScanner scanner;
    private final TicketSlaProperties properties;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ScheduledFuture<?> scheduled;

    public TicketSlaPollingWorker(TicketSlaScanner scanner, TicketSlaProperties properties,
                                  TaskScheduler scheduler, Clock clock) {
        this.scanner = scanner;
        this.properties = properties;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @Override public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            schedule(Duration.ZERO);
            LOG.info("Ticket SLA scanner is ready");
        }
    }

    private void poll() {
        if (!running.get()) return;
        try { scanner.scanOnce(); }
        catch (RuntimeException ignored) { LOG.warn("SLA scheduler poll failed; retrying boundedly"); }
        schedule(properties.getPollInterval());
    }

    private synchronized void schedule(Duration delay) {
        if (!running.get()) return;
        scheduled = scheduler.schedule(this::poll, clock.instant().plus(delay));
    }

    @Override public synchronized void stop() {
        running.set(false);
        if (scheduled != null) { scheduled.cancel(false); scheduled = null; }
    }
    @Override public void stop(Runnable callback) { try { stop(); } finally { callback.run(); } }
    @Override public boolean isRunning() { return running.get(); }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 150; }
}
