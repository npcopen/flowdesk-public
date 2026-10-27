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

/**
 * 通知 Outbox 的单线程自适应轮询器，根据本轮是否处理到消息调整下一轮等待时间。
 */
public final class WorkflowNotificationOutboxPollingWorker implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowNotificationOutboxPollingWorker.class);
    private final WorkflowNotificationOutboxPublisher publisher; private final TaskScheduler scheduler;
    private final Clock clock; private final AtomicBoolean running = new AtomicBoolean();
    private volatile ScheduledFuture<?> scheduled;
    /**
     * 绑定通知发布器、调度器和轮询时钟，负责按配置间隔驱动发布循环及生命周期停止。
     */
    public WorkflowNotificationOutboxPollingWorker(WorkflowNotificationOutboxPublisher publisher,
                                                   TaskScheduler scheduler, Clock clock) {
        this.publisher = publisher; this.scheduler = scheduler; this.clock = clock;
    }
    /**
     * 绑定通知发布器、调度器和轮询时钟，负责按配置间隔驱动发布循环及生命周期停止。
     */
    public WorkflowNotificationOutboxPollingWorker(WorkflowNotificationOutboxPublisher publisher,
                                                   TaskScheduler scheduler) {
        this(publisher, scheduler, Clock.systemUTC());
    }
    /**
     * 启动当前组件并建立消息连接或调度资源，准备处理生命周期内的任务。
     */
    @Override public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            schedule(Duration.ZERO);
            LOG.info("Workflow Notification Outbox publisher is ready");
        }
    }
    /**
     * 执行一轮待处理消息扫描和发布，按运行状态安排后续轮询。
     */
    private void poll() {
        if (!running.get()) return; Duration delay = NotificationEventContract.EMPTY_POLL_INTERVAL;
        try { if (publisher.publishOnce().scanned() > 0) delay = Duration.ZERO; }
        catch (RuntimeException failure) { LOG.warn("Workflow Notification Outbox poll failed; retrying"); }
        schedule(delay);
    }
    /**
     * 安排下次扫描，使轮询遵循配置间隔并能在关闭时取消。
     */
    private synchronized void schedule(Duration delay) {
        if (running.get()) scheduled = scheduler.schedule(this::poll, clock.instant().plus(delay));
    }
    /**
     * 停止组件并释放连接或调度资源，阻止关闭后继续领取任务。
     */
    @Override public synchronized void stop() { running.set(false); if (scheduled != null) scheduled.cancel(false); scheduled = null; }
    /**
     * 停止组件并释放连接或调度资源，阻止关闭后继续领取任务。
     */
    @Override public void stop(Runnable callback) { try { stop(); } finally { callback.run(); } }
    /**
     * 返回组件运行状态，供 Spring 生命周期管理决定启动和停止行为。
     */
    @Override public boolean isRunning() { return running.get(); }
    /**
     * 返回是否随应用自动启动，保持后台处理与功能开关一致。
     */
    @Override public boolean isAutoStartup() { return true; }
    /**
     * 返回生命周期阶段，使组件按约定顺序启动并反序停止。
     */
    @Override public int getPhase() { return Integer.MAX_VALUE - 120; }
}
