package com.flowdesk.alert.event;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Configuration(proxyBeanMethods=false)@EnableConfigurationProperties(AlertOutboxProperties.class)
@ConditionalOnProperty(prefix="flowdesk.alert.outbox.publisher",name="enabled",havingValue="true")
class AlertOutboxConfiguration {
    @Bean AlertOutboxRepository alertOutboxRepository(org.springframework.jdbc.core.JdbcTemplate jdbc){return new AlertOutboxRepository(jdbc);}
    @Bean(initMethod="start",destroyMethod="close")AlertOutboxPublisher alertOutboxPublisher(AlertOutboxRepository r,PlatformTransactionManager tx,AlertOutboxProperties p){return new AlertOutboxPublisher(r,tx,p);}
    @Bean(name="alertOutboxScheduler")ThreadPoolTaskScheduler alertOutboxScheduler(){var s=new ThreadPoolTaskScheduler();s.setPoolSize(1);s.setThreadNamePrefix("alert-outbox-");s.setWaitForTasksToCompleteOnShutdown(true);return s;}
    @Bean AlertOutboxWorker alertOutboxWorker(AlertOutboxPublisher p,@Qualifier("alertOutboxScheduler")TaskScheduler s){return new AlertOutboxWorker(p,s);}
    static final class AlertOutboxWorker implements SmartLifecycle{private static final Logger LOGGER=LoggerFactory.getLogger(AlertOutboxWorker.class);private final AlertOutboxPublisher publisher;private final TaskScheduler scheduler;private final AtomicBoolean running=new AtomicBoolean();private volatile ScheduledFuture<?> task;
        AlertOutboxWorker(AlertOutboxPublisher p,TaskScheduler s){publisher=p;scheduler=s;}public synchronized void start(){if(running.compareAndSet(false,true)){schedule(Instant.now());LOGGER.info("Incident Alert Outbox publishers are ready");}}
        private void schedule(Instant at){if(running.get())task=scheduler.schedule(()->{long delay=500;try{var r=publisher.publishOnce();delay=r.scanned()==0?500:0;}catch(RuntimeException failure){LOGGER.warn("Alert Outbox poll failed; retrying after the fixed empty-poll interval");}schedule(Instant.now().plusMillis(delay));},at);}
        public synchronized void stop(){running.set(false);if(task!=null)task.cancel(false);}public boolean isRunning(){return running.get();}public boolean isAutoStartup(){return true;}public int getPhase(){return Integer.MAX_VALUE-90;}}
}
