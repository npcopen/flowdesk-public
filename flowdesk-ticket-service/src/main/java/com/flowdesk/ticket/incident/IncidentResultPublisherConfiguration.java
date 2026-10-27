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

/** 按显式开关装配 Incident 建单结果发布器及其轮询调度，统一管理传输生命周期。 */
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(IncidentResultPublisherProperties.class)
@ConditionalOnProperty(prefix="flowdesk.ticket.incident-result-publisher",name="enabled",havingValue="true")
class IncidentResultPublisherConfiguration {
    /**
     * 装配告警建单的 incidentResultTransport 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean(initMethod="start",destroyMethod="close") IncidentResultRocketMqTransport incidentResultTransport(IncidentResultPublisherProperties p){return IncidentResultRocketMqTransport.create(p);}
    /**
     * 装配告警建单的 incidentResultDispatchService 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean IncidentResultDispatchService incidentResultDispatchService(IncidentResultOutboxPersistenceMapper mapper){return new com.flowdesk.ticket.incident.impl.IncidentResultDispatchServiceImpl(mapper);}
    /**
     * 装配告警建单的 incidentResultPublisher 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean IncidentResultPublisher incidentResultPublisher(IncidentResultDispatchService r,IncidentResultRocketMqTransport t,PlatformTransactionManager tx){return new IncidentResultPublisher(r,t,tx);}
    /**
     * 装配告警建单的 incidentResultScheduler 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean(name="incidentResultScheduler") ThreadPoolTaskScheduler incidentResultScheduler(){var s=new ThreadPoolTaskScheduler();s.setPoolSize(1);s.setThreadNamePrefix("incident-result-");s.setWaitForTasksToCompleteOnShutdown(true);return s;}
    /**
     * 装配告警建单的 incidentResultWorker 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean IncidentResultWorker incidentResultWorker(IncidentResultPublisher p,@Qualifier("incidentResultScheduler") TaskScheduler incidentResultScheduler){return new IncidentResultWorker(p,incidentResultScheduler);}
    /**
     * 告警结果发布的后台轮询器，管理启动、停止与下一轮调度。
     */
    static final class IncidentResultWorker implements SmartLifecycle {
        private static final Logger LOGGER=LoggerFactory.getLogger(IncidentResultWorker.class);private final IncidentResultPublisher publisher;private final TaskScheduler scheduler;private final AtomicBoolean running=new AtomicBoolean();private volatile ScheduledFuture<?> task;
        /**
         * 构造告警建单组件并保存调用依赖。
         */
        IncidentResultWorker(IncidentResultPublisher p,TaskScheduler s){publisher=p;scheduler=s;}
        /**
         * 启动后台组件并注册消费或轮询任务，通过运行标记避免重复启动。
         */
        public synchronized void start(){if(running.compareAndSet(false,true)){schedule(Instant.now());LOGGER.info("Incident Ticket result Outbox publisher is ready");}}
        /**
         * 注册下一次任务，停止状态下不再继续安排轮询。
         */
        private void schedule(Instant at){if(running.get())task=scheduler.schedule(()->{long delay=500;try{var r=publisher.publishOnce();delay=r.scanned()==0?500:0;}catch(RuntimeException failure){LOGGER.warn("Incident result Outbox poll failed; retrying after the fixed empty-poll interval");}schedule(Instant.now().plusMillis(delay));},at);}
        /**
         * 停止后台组件并取消任务或释放资源，存在回调时通知停止完成。
         */
        public synchronized void stop(){running.set(false);if(task!=null)task.cancel(false);}
        /**
         * 返回运行标记，供 Spring 生命周期和调度分支判断。
         */
        public boolean isRunning(){return running.get();}
        /**
         * 声明该组件是否随 Spring 上下文自动启动。
         */
        public boolean isAutoStartup(){return true;}
        /**
         * 返回生命周期阶段，决定关联组件启动和停止顺序。
         */
        public int getPhase(){return Integer.MAX_VALUE-90;}
    }
}
