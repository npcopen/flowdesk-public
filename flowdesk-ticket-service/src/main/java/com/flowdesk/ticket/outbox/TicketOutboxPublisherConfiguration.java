package com.flowdesk.ticket.outbox;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

/** 流程启动消息发布的显式启用配置；默认关闭，避免应用启动时隐式连接 MQ。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TicketOutboxPublisherProperties.class)
@ConditionalOnProperty(
        prefix = "flowdesk.ticket.outbox.publisher",
        name = "enabled",
        havingValue = "true")
public class TicketOutboxPublisherConfiguration {

    /**
     * 装配工作流启动发布的 ticketOutboxTransport 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    public RocketMqTicketOutboxTransport ticketOutboxTransport(
            TicketOutboxPublisherProperties properties) {
        return RocketMqTicketOutboxTransport.create(properties);
    }

    /**
     * 装配工作流启动发布的 ticketOutboxPublisher 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean
    public TicketOutboxPublisher ticketOutboxPublisher(
            WorkflowStartDispatchService repository,
            RocketMqTicketOutboxTransport transport,
            PlatformTransactionManager transactionManager) {
        return new TicketOutboxPublisher(repository, transport, transactionManager);
    }

    /**
     * 装配工作流启动发布的 ticketOutboxTaskScheduler 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean(name = "ticketOutboxTaskScheduler")
    public ThreadPoolTaskScheduler ticketOutboxTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ticket-outbox-publisher-");
        scheduler.setPhase(Integer.MAX_VALUE - 200);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }

    /**
     * 装配工作流启动发布的 ticketOutboxPollingWorker 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean
    public TicketOutboxPollingWorker ticketOutboxPollingWorker(
            TicketOutboxPublisher publisher,
            @Qualifier("ticketOutboxTaskScheduler") TaskScheduler scheduler) {
        return new TicketOutboxPollingWorker(publisher, scheduler);
    }
}
