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

/**
 * 工单模块回归测试，覆盖该组件的正常流程、边界条件与关键一致性约束。
 */
class TicketOutboxPollingWorkerTest {

    private static final Instant NOW = Instant.parse("2026-08-23T01:02:03Z");

    /**
     * 验证轮询立即启动，并在空轮询后准确等待五百毫秒。
     */
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

    /**
     * 验证有工作时立即续跑，失败后使用空闲延迟继续。
     */
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

    /**
     * 创建本场景需要的发送或发布统计结果，精确控制调度分支。
     */
    private static TicketOutboxPublisher.PublishRunResult result(int scanned) {
        return new TicketOutboxPublisher.PublishRunResult(
                scanned, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * 用于该测试的内部替身，提供可控制的时钟、调度或依赖行为。
     */
    private record ScheduledCall(Runnable task, Instant startTime, FakeScheduledFuture future) {
    }

    /**
     * 用于该测试的内部替身，提供可控制的时钟、调度或依赖行为。
     */
    private static final class FakeScheduler implements TicketOutboxPollingWorker.OneShotScheduler {
        private final List<ScheduledCall> calls = new ArrayList<>();

        /**
         * 记录待执行任务与计划时间，并返回可取消的测试 Future。
         */
        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            FakeScheduledFuture future = new FakeScheduledFuture();
            calls.add(new ScheduledCall(task, startTime, future));
            return future;
        }

        /**
         * 执行已记录测试任务，供断言下一轮调度及延迟选择。
         */
        private void run(int index) {
            calls.get(index).task().run();
        }
    }

    /**
     * 用于该测试的内部替身，提供可控制的时钟、调度或依赖行为。
     */
    private static final class FakeScheduledFuture implements ScheduledFuture<Object> {
        private boolean cancelled;

        /**
         * 返回测试 Future 的固定剩余延迟，满足调度器接口约定。
         */
        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        /**
         * 按测试 Future 的简化排序约定比较延迟对象。
         */
        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        /**
         * 将测试 Future 标记为已取消，供验证停止时取消待执行任务。
         */
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        /**
         * 返回测试 Future 的取消标记。
         */
        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        /**
         * 返回测试 Future 的完成标记，供调度行为断言。
         */
        @Override
        public boolean isDone() {
            return cancelled;
        }

        /**
         * 按测试替身约定直接返回空值，不执行阻塞等待。
         */
        @Override
        public Object get() throws InterruptedException, ExecutionException {
            return null;
        }

        /**
         * 按测试替身约定直接返回空值，不执行阻塞等待。
         */
        @Override
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return null;
        }
    }

    /**
     * 用于该测试的内部替身，提供可控制的时钟、调度或依赖行为。
     */
    private static final class FixedTestClock extends Clock {
        private final Instant instant;

        /**
         * 初始化工作流启动发布测试替身，保存当前场景使用的可控状态与依赖。
         */
        private FixedTestClock(Instant instant) {
            this.instant = instant;
        }

        /**
         * 返回测试时钟使用的时区。
         */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /**
         * 按测试时钟约定返回指定时区视图，保持可控时间不变。
         */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /**
         * 取得当前规范精度时间。
         */
        @Override
        public Instant instant() {
            return instant;
        }
    }
}
