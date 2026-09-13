package com.flowdesk.ticket.incident;

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

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(IncidentResultPublisherProperties.class)
@ConditionalOnProperty(prefix="flowdesk.ticket.incident-result-publisher",name="enabled",havingValue="true")
class IncidentResultPublisherConfiguration {
    @Bean(initMethod="start",destroyMethod="close") IncidentResultRocketMqTransport incidentResultTransport(IncidentResultPublisherProperties p){return IncidentResultRocketMqTransport.create(p);}
    @Bean IncidentResultOutboxRepository incidentResultOutboxRepository(org.springframework.jdbc.core.JdbcTemplate jdbc){return new IncidentResultOutboxRepository(jdbc);}
    @Bean IncidentResultPublisher incidentResultPublisher(IncidentResultOutboxRepository r,IncidentResultRocketMqTransport t,PlatformTransactionManager tx){return new IncidentResultPublisher(r,t,tx);}
    @Bean(name="incidentResultScheduler") ThreadPoolTaskScheduler incidentResultScheduler(){var s=new ThreadPoolTaskScheduler();s.setPoolSize(1);s.setThreadNamePrefix("incident-result-");s.setWaitForTasksToCompleteOnShutdown(true);return s;}
    @Bean IncidentResultWorker incidentResultWorker(IncidentResultPublisher p,@Qualifier("incidentResultScheduler") TaskScheduler incidentResultScheduler){return new IncidentResultWorker(p,incidentResultScheduler);}
    static final class IncidentResultWorker implements SmartLifecycle {
        private static final Logger LOGGER=LoggerFactory.getLogger(IncidentResultWorker.class);private final IncidentResultPublisher publisher;private final TaskScheduler scheduler;private final AtomicBoolean running=new AtomicBoolean();private volatile ScheduledFuture<?> task;
        IncidentResultWorker(IncidentResultPublisher p,TaskScheduler s){publisher=p;scheduler=s;}
        public synchronized void start(){if(running.compareAndSet(false,true)){schedule(Instant.now());LOGGER.info("Incident Ticket result Outbox publisher is ready");}}
        private void schedule(Instant at){if(running.get())task=scheduler.schedule(()->{long delay=500;try{var r=publisher.publishOnce();delay=r.scanned()==0?500:0;}catch(RuntimeException failure){LOGGER.warn("Incident result Outbox poll failed; retrying after the fixed empty-poll interval");}schedule(Instant.now().plusMillis(delay));},at);}
        public synchronized void stop(){running.set(false);if(task!=null)task.cancel(false);}
        public boolean isRunning(){return running.get();}public boolean isAutoStartup(){return true;}public int getPhase(){return Integer.MAX_VALUE-90;}
    }
}
