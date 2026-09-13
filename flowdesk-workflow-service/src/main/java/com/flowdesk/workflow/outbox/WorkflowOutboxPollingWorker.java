package com.flowdesk.workflow.outbox;

import com.flowdesk.workflow.event.WorkflowReverseEventContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WorkflowOutboxPollingWorker implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowOutboxPollingWorker.class);
    private final WorkflowOutboxPublisher publisher;
    private final TaskScheduler scheduler;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ScheduledFuture<?> scheduled;

    WorkflowOutboxPollingWorker(WorkflowOutboxPublisher publisher, TaskScheduler scheduler) {
        this.publisher = publisher;
        this.scheduler = scheduler;
    }

    @Override public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            schedule(Duration.ZERO);
            LOG.info("Workflow reverse Outbox publisher is ready");
        }
    }

    private void poll() {
        if (!running.get()) return;
        Duration delay = WorkflowReverseEventContract.EMPTY_POLL_INTERVAL;
        try {
            if (publisher.publishOnce().scanned() > 0) delay = Duration.ZERO;
        }
        catch (RuntimeException failure) {
            LOG.warn("Workflow Outbox poll failed; retrying at the fixed interval");
        }
        schedule(delay);
    }

    private synchronized void schedule(Duration delay) {
        if (running.get()) scheduled = scheduler.schedule(this::poll, Instant.now().plus(delay));
    }

    @Override public synchronized void stop() {
        running.set(false);
        if (scheduled != null) scheduled.cancel(false);
        scheduled = null;
    }
    @Override public boolean isRunning() { return running.get(); }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
