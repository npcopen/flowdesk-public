package com.flowdesk.ticket.sla;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** 独立且需显式启用的索引重建循环，避免重建工作挤占正常 SLA 扫描的批次预算。 */
public final class SlaRebuildPollingWorker implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(SlaRebuildPollingWorker.class);
    private final SlaIndexRebuilder rebuilder;
    private final TicketSlaProperties properties;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ScheduledFuture<?> scheduled;

    /**
     * 构造SLA组件并保存调用依赖；支持注入时钟以控制时间边界。
     */
    public SlaRebuildPollingWorker(SlaIndexRebuilder rebuilder, TicketSlaProperties properties,
                                   TaskScheduler scheduler, Clock clock) {
        this.rebuilder = rebuilder; this.properties = properties; this.scheduler = scheduler; this.clock = clock;
    }
    /**
     * 启动后台组件并注册消费或轮询任务，通过运行标记避免重复启动。
     */
    @Override public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            schedule(Duration.ZERO);
            LOG.info("Ticket SLA index rebuilder is ready");
        }
    }
    /**
     * 执行一轮后台扫描或刷新，并依据运行状态安排后续任务。
     */
    private void poll() {
        if (!running.get()) return;
        try { rebuilder.rebuildOnce(); } catch (RuntimeException ignored) { LOG.warn("SLA index rebuild poll failed"); }
        schedule(Duration.ofMinutes(5));
    }
    /**
     * 注册下一次任务，停止状态下不再继续安排轮询。
     */
    private synchronized void schedule(Duration delay) {
        if (running.get()) scheduled = scheduler.schedule(this::poll, clock.instant().plus(delay));
    }
    /**
     * 停止后台组件并取消任务或释放资源，存在回调时通知停止完成。
     */
    @Override public synchronized void stop() { running.set(false); if (scheduled != null) { scheduled.cancel(false); scheduled = null; } }
    /**
     * 停止后台组件并取消任务或释放资源，存在回调时通知停止完成。
     */
    @Override public void stop(Runnable callback) { try { stop(); } finally { callback.run(); } }
    /**
     * 返回运行标记，供 Spring 生命周期和调度分支判断。
     */
    @Override public boolean isRunning() { return running.get(); }
    /**
     * 声明该组件是否随 Spring 上下文自动启动。
     */
    @Override public boolean isAutoStartup() { return true; }
    /**
     * 返回生命周期阶段，决定关联组件启动和停止顺序。
     */
    @Override public int getPhase() { return Integer.MAX_VALUE - 140; }
}
