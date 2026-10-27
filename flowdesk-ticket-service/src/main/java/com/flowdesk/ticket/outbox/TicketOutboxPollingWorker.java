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

/** 单线程自适应发布调度：有工作时立即继续，空轮询后等待 500 毫秒。 */
public final class TicketOutboxPollingWorker implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(TicketOutboxPollingWorker.class);

    private final Supplier<TicketOutboxPublisher.PublishRunResult> poll;
    private final OneShotScheduler scheduler;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ScheduledFuture<?> scheduled;

    /**
     * 构造工作流启动发布组件并保存调用依赖。
     */
    public TicketOutboxPollingWorker(TicketOutboxPublisher publisher, TaskScheduler scheduler) {
        this(publisher::publishOnce,
                (task, startTime) -> scheduler.schedule(task, startTime),
                Clock.systemUTC());
    }

    /**
     * 构造工作流启动发布组件并保存调用依赖；支持注入时钟以控制时间边界。
     */
    TicketOutboxPollingWorker(
            Supplier<TicketOutboxPublisher.PublishRunResult> poll,
            OneShotScheduler scheduler,
            Clock clock) {
        this.poll = Objects.requireNonNull(poll, "poll");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 启动后台组件并注册消费或轮询任务，通过运行标记避免重复启动。
     */
    @Override
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            scheduleNext(Duration.ZERO);
        }
    }

    /**
     * 执行一轮发布，按有无工作及失败情况决定下次轮询延迟。
     */
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
            // 持久化错误及指标仅使用稳定分类，此处不记录可能含敏感信息的异常文本。
            LOGGER.warn("Ticket Outbox poll failed; retrying after the fixed empty-poll interval");
        }
        scheduleNext(delay);
    }

    /**
     * 依据本轮结果安排发布轮询，空闲时使用有界等待。
     */
    private synchronized void scheduleNext(Duration delay) {
        if (!running.get()) {
            return;
        }
        Instant nextStart = clock.instant().plus(delay);
        scheduled = scheduler.schedule(this::pollAndReschedule, nextStart);
    }

    /**
     * 停止后台组件并取消任务或释放资源，存在回调时通知停止完成。
     */
    @Override
    public synchronized void stop() {
        running.set(false);
        ScheduledFuture<?> task = scheduled;
        scheduled = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    /**
     * 停止后台组件并取消任务或释放资源，存在回调时通知停止完成。
     */
    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        }
        finally {
            callback.run();
        }
    }

    /**
     * 返回运行标记，供 Spring 生命周期和调度分支判断。
     */
    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 声明该组件是否随 Spring 上下文自动启动。
     */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /**
     * 返回生命周期阶段，决定关联组件启动和停止顺序。
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    /**
     * 一次性延迟调度契约，供轮询器精确安排后续执行时间。
     */
    @FunctionalInterface
    interface OneShotScheduler {
        /**
         * 注册下一次任务，停止状态下不再继续安排轮询。
         */
        ScheduledFuture<?> schedule(Runnable task, Instant startTime);
    }
}
