package com.flowdesk.workflow.notification;

import com.flowdesk.common.notification.NotificationEventContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adaptive, single-threaded polling loop for the Notification Outbox. */
public final class WorkflowNotificationOutboxPollingWorker implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowNotificationOutboxPollingWorker.class);
    private final WorkflowNotificationOutboxPublisher publisher; private final TaskScheduler scheduler;
    private final Clock clock; private final AtomicBoolean running = new AtomicBoolean();
    private volatile ScheduledFuture<?> scheduled;
    public WorkflowNotificationOutboxPollingWorker(WorkflowNotificationOutboxPublisher publisher,
                                                   TaskScheduler scheduler, Clock clock) {
        this.publisher = publisher; this.scheduler = scheduler; this.clock = clock;
    }
    public WorkflowNotificationOutboxPollingWorker(WorkflowNotificationOutboxPublisher publisher,
                                                   TaskScheduler scheduler) {
        this(publisher, scheduler, Clock.systemUTC());
    }
    @Override public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            schedule(Duration.ZERO);
            LOG.info("Workflow Notification Outbox publisher is ready");
        }
    }
    private void poll() {
        if (!running.get()) return; Duration delay = NotificationEventContract.EMPTY_POLL_INTERVAL;
        try { if (publisher.publishOnce().scanned() > 0) delay = Duration.ZERO; }
        catch (RuntimeException failure) { LOG.warn("Workflow Notification Outbox poll failed; retrying"); }
        schedule(delay);
    }
    private synchronized void schedule(Duration delay) {
        if (running.get()) scheduled = scheduler.schedule(this::poll, clock.instant().plus(delay));
    }
    @Override public synchronized void stop() { running.set(false); if (scheduled != null) scheduled.cancel(false); scheduled = null; }
    @Override public void stop(Runnable callback) { try { stop(); } finally { callback.run(); } }
    @Override public boolean isRunning() { return running.get(); }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 120; }
}
