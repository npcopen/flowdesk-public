package com.flowdesk.ticket.outbox;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

/** Opt-in production wiring. Disabled by default so application startup never contacts MQ implicitly. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TicketOutboxPublisherProperties.class)
@ConditionalOnProperty(
        prefix = "flowdesk.ticket.outbox.publisher",
        name = "enabled",
        havingValue = "true")
public class TicketOutboxPublisherConfiguration {

    @Bean(initMethod = "start", destroyMethod = "close")
    public RocketMqTicketOutboxTransport ticketOutboxTransport(
            TicketOutboxPublisherProperties properties) {
        return RocketMqTicketOutboxTransport.create(properties);
    }

    @Bean
    public TicketOutboxPublisher ticketOutboxPublisher(
            TicketOutboxRepository repository,
            RocketMqTicketOutboxTransport transport,
            PlatformTransactionManager transactionManager) {
        return new TicketOutboxPublisher(repository, transport, transactionManager);
    }

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

    @Bean
    public TicketOutboxPollingWorker ticketOutboxPollingWorker(
            TicketOutboxPublisher publisher,
            @Qualifier("ticketOutboxTaskScheduler") TaskScheduler scheduler) {
        return new TicketOutboxPollingWorker(publisher, scheduler);
    }
}
