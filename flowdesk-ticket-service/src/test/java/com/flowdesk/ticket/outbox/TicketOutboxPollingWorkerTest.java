package com.flowdesk.ticket.outbox;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class TicketOutboxPollingWorkerTest {

    private static final Instant NOW = Instant.parse("2026-08-23T01:02:03Z");

    @Test
    void startsImmediatelyAndWaitsExactlyFiveHundredMillisAfterEmptyPoll() {
        FakeScheduler scheduler = new FakeScheduler();
        TicketOutboxPollingWorker worker = new TicketOutboxPollingWorker(
                () -> result(0), scheduler, new FixedTestClock(NOW));

        worker.start();
        assertThat(worker.isRunning()).isTrue();
        assertThat(worker.isAutoStartup()).isTrue();
        assertThat(scheduler.calls).singleElement()
                .extracting(ScheduledCall::startTime).isEqualTo(NOW);

        scheduler.run(0);
        assertThat(scheduler.calls).hasSize(2);
        assertThat(scheduler.calls.get(1).startTime()).isEqualTo(NOW.plusMillis(500));

        worker.stop();
        assertThat(worker.isRunning()).isFalse();
        assertThat(scheduler.calls.get(1).future().isCancelled()).isTrue();
    }

    @Test
    void continuesImmediatelyAfterWorkAndUsesEmptyDelayAfterFailure() {
        FakeScheduler scheduler = new FakeScheduler();
        Queue<Supplier<TicketOutboxPublisher.PublishRunResult>> polls = new ArrayDeque<>();
        polls.add(() -> result(1));
        polls.add(() -> {
            throw new IllegalStateException("sensitive broker detail must not escape");
        });
        TicketOutboxPollingWorker worker = new TicketOutboxPollingWorker(
                () -> polls.remove().get(), scheduler, new FixedTestClock(NOW));

        worker.start();
        scheduler.run(0);
        assertThat(scheduler.calls.get(1).startTime()).isEqualTo(NOW);

        scheduler.run(1);
        assertThat(scheduler.calls.get(2).startTime()).isEqualTo(NOW.plusMillis(500));
        worker.stop();
    }

    private static TicketOutboxPublisher.PublishRunResult result(int scanned) {
        return new TicketOutboxPublisher.PublishRunResult(
                scanned, 0, 0, 0, 0, 0, 0, 0);
    }

    private record ScheduledCall(Runnable task, Instant startTime, FakeScheduledFuture future) {
    }

    private static final class FakeScheduler implements TicketOutboxPollingWorker.OneShotScheduler {
        private final List<ScheduledCall> calls = new ArrayList<>();

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            FakeScheduledFuture future = new FakeScheduledFuture();
            calls.add(new ScheduledCall(task, startTime, future));
            return future;
        }

        private void run(int index) {
            calls.get(index).task().run();
        }
    }

    private static final class FakeScheduledFuture implements ScheduledFuture<Object> {
        private boolean cancelled;

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return null;
        }
    }

    private static final class FixedTestClock extends Clock {
        private final Instant instant;

        private FixedTestClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
