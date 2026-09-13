package com.flowdesk.ticket.notification;

import com.flowdesk.common.notification.NotificationEventContract;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;import org.springframework.context.SmartLifecycle;import org.springframework.scheduling.TaskScheduler;
import java.time.Clock;import java.time.Duration;import java.util.concurrent.ScheduledFuture;import java.util.concurrent.atomic.AtomicBoolean;

public final class TicketNotificationOutboxPollingWorker implements SmartLifecycle{
    private static final Logger LOG=LoggerFactory.getLogger(TicketNotificationOutboxPollingWorker.class);private final TicketNotificationOutboxPublisher publisher;private final TaskScheduler scheduler;private final Clock clock;private final AtomicBoolean running=new AtomicBoolean();private volatile ScheduledFuture<?> scheduled;
    public TicketNotificationOutboxPollingWorker(TicketNotificationOutboxPublisher p,TaskScheduler s,Clock c){publisher=p;scheduler=s;clock=c;}public TicketNotificationOutboxPollingWorker(TicketNotificationOutboxPublisher p,TaskScheduler s){this(p,s,Clock.systemUTC());}
    @Override public synchronized void start(){if(running.compareAndSet(false,true)){schedule(Duration.ZERO);LOG.info("Ticket Notification Outbox publisher is ready");}}private void poll(){if(!running.get())return;Duration delay=NotificationEventContract.EMPTY_POLL_INTERVAL;try{if(publisher.publishOnce().scanned()>0)delay=Duration.ZERO;}catch(RuntimeException e){LOG.warn("Ticket Notification Outbox poll failed; retrying");}schedule(delay);}private synchronized void schedule(Duration d){if(running.get())scheduled=scheduler.schedule(this::poll,clock.instant().plus(d));}
    @Override public synchronized void stop(){running.set(false);if(scheduled!=null)scheduled.cancel(false);scheduled=null;}@Override public void stop(Runnable c){try{stop();}finally{c.run();}}@Override public boolean isRunning(){return running.get();}@Override public boolean isAutoStartup(){return true;}@Override public int getPhase(){return Integer.MAX_VALUE-110;}
}
