package com.flowdesk.ticket.notification;

import com.flowdesk.common.notification.NotificationEventContract;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;import org.springframework.context.SmartLifecycle;import org.springframework.scheduling.TaskScheduler;
import java.time.Clock;import java.time.Duration;import java.util.concurrent.ScheduledFuture;import java.util.concurrent.atomic.AtomicBoolean;

/** 工单通知发布器的生命周期与轮询调度，控制任务启动、停止和下一次运行。 */
public final class TicketNotificationOutboxPollingWorker implements SmartLifecycle{
    private static final Logger LOG=LoggerFactory.getLogger(TicketNotificationOutboxPollingWorker.class);private final TicketNotificationOutboxPublisher publisher;private final TaskScheduler scheduler;private final Clock clock;private final AtomicBoolean running=new AtomicBoolean();private volatile ScheduledFuture<?> scheduled;
    /**
     * 构造工单通知组件并保存调用依赖；支持注入时钟以控制时间边界。
     */
    public TicketNotificationOutboxPollingWorker(TicketNotificationOutboxPublisher p,TaskScheduler s,Clock c){publisher=p;scheduler=s;clock=c;}
    /**
     * 构造工单通知组件并保存调用依赖。
     */
    public TicketNotificationOutboxPollingWorker(TicketNotificationOutboxPublisher p,TaskScheduler s){this(p,s,Clock.systemUTC());}
    /**
     * 启动后台组件并注册消费或轮询任务，通过运行标记避免重复启动。
     */
    @Override public synchronized void start(){if(running.compareAndSet(false,true)){schedule(Duration.ZERO);LOG.info("Ticket Notification Outbox publisher is ready");}}
    /**
     * 执行一轮后台扫描或刷新，并依据运行状态安排后续任务。
     */
    private void poll(){if(!running.get())return;Duration delay=NotificationEventContract.EMPTY_POLL_INTERVAL;try{if(publisher.publishOnce().scanned()>0)delay=Duration.ZERO;}catch(RuntimeException e){LOG.warn("Ticket Notification Outbox poll failed; retrying");}schedule(delay);}
    /**
     * 注册下一次任务，停止状态下不再继续安排轮询。
     */
    private synchronized void schedule(Duration d){if(running.get())scheduled=scheduler.schedule(this::poll,clock.instant().plus(d));}
    /**
     * 停止后台组件并取消任务或释放资源，存在回调时通知停止完成。
     */
    @Override public synchronized void stop(){running.set(false);if(scheduled!=null)scheduled.cancel(false);scheduled=null;}
    /**
     * 停止后台组件并取消任务或释放资源，存在回调时通知停止完成。
     */
    @Override public void stop(Runnable c){try{stop();}finally{c.run();}}
    /**
     * 返回运行标记，供 Spring 生命周期和调度分支判断。
     */
    @Override public boolean isRunning(){return running.get();}
    /**
     * 声明该组件是否随 Spring 上下文自动启动。
     */
    @Override public boolean isAutoStartup(){return true;}
    /**
     * 返回生命周期阶段，决定关联组件启动和停止顺序。
     */
    @Override public int getPhase(){return Integer.MAX_VALUE-110;}
}
