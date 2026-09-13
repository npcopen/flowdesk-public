package com.flowdesk.ticket.outbox;

import com.flowdesk.ticket.event.TicketEventContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Single-threaded adaptive runner: continue immediately after work, wait 500 ms after an empty poll. */
public final class TicketOutboxPollingWorker implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(TicketOutboxPollingWorker.class);

    private final Supplier<TicketOutboxPublisher.PublishRunResult> poll;
    private final OneShotScheduler scheduler;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ScheduledFuture<?> scheduled;

    public TicketOutboxPollingWorker(TicketOutboxPublisher publisher, TaskScheduler scheduler) {
        this(publisher::publishOnce,
                (task, startTime) -> scheduler.schedule(task, startTime),
                Clock.systemUTC());
    }

    TicketOutboxPollingWorker(
            Supplier<TicketOutboxPublisher.PublishRunResult> poll,
            OneShotScheduler scheduler,
            Clock clock) {
        this.poll = Objects.requireNonNull(poll, "poll");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            scheduleNext(Duration.ZERO);
        }
    }

    private void pollAndReschedule() {
        if (!running.get()) {
            return;
        }
        Duration delay = TicketEventContract.WorkflowStartRequested.EMPTY_POLL_INTERVAL;
        try {
            TicketOutboxPublisher.PublishRunResult result = Objects.requireNonNull(poll.get());
            if (result.scanned() > 0) {
                delay = Duration.ZERO;
            }
        }
        catch (RuntimeException failure) {
            // Persisted errors and metrics carry stable classifications; never log exception text here.
            LOGGER.warn("Ticket Outbox poll failed; retrying after the fixed empty-poll interval");
        }
        scheduleNext(delay);
    }

    private synchronized void scheduleNext(Duration delay) {
        if (!running.get()) {
            return;
        }
        Instant nextStart = clock.instant().plus(delay);
        scheduled = scheduler.schedule(this::pollAndReschedule, nextStart);
    }

    @Override
    public synchronized void stop() {
        running.set(false);
        ScheduledFuture<?> task = scheduled;
        scheduled = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        }
        finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    @FunctionalInterface
    interface OneShotScheduler {
        ScheduledFuture<?> schedule(Runnable task, Instant startTime);
    }
}
