package com.flowdesk.ticket.sla;

import com.flowdesk.ticket.repository.TicketRepository;
import com.flowdesk.ticket.service.TicketService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/** M8 SLA scanner/index wiring; all workers are disabled unless explicitly enabled. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TicketSlaProperties.class)
public class TicketSlaConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "flowdesk.ticket.sla", name = "redis-enabled", havingValue = "true")
    RedisSlaDueIndex redisSlaDueIndex(StringRedisTemplate redis) {
        return new RedisSlaDueIndex(redis);
    }

    @Bean
    @ConditionalOnMissingBean(SlaDueIndex.class)
    NoopSlaDueIndex noopSlaDueIndex() {
        return new NoopSlaDueIndex();
    }

    @Bean
    SlaIndexProjection slaIndexProjection(TicketRepository repository, SlaDueIndex index,
                                          Clock clock) {
        return new SlaIndexProjection(repository, index, clock);
    }

    @Bean
    SlaTargetConfigurationGate slaTargetConfigurationGate(
            TicketRepository repository, SlaTargetQualificationService qualification, Clock clock) {
        return new SlaTargetConfigurationGate(repository, qualification, clock);
    }

    @Bean(name = "ticketSlaTaskScheduler")
    ThreadPoolTaskScheduler ticketSlaTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ticket-sla-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(prefix = "flowdesk.ticket.sla", name = "scanner-enabled", havingValue = "true")
    TicketSlaScanner ticketSlaScanner(TicketRepository repository, TicketService ticketService,
                                     SlaDueIndex index, TicketSlaProperties properties,
                                     Clock clock) {
        properties.validate();
        return new TicketSlaScanner(repository, ticketService, index, properties, clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "flowdesk.ticket.sla", name = "scanner-enabled", havingValue = "true")
    TicketSlaPollingWorker ticketSlaPollingWorker(
            TicketSlaScanner scanner, TicketSlaProperties properties,
            @Qualifier("ticketSlaTaskScheduler") ThreadPoolTaskScheduler scheduler, Clock clock) {
        return new TicketSlaPollingWorker(scanner, properties, scheduler, clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "flowdesk.ticket.sla", name = "enabled", havingValue = "true")
    SlaQualificationRefreshWorker slaQualificationRefreshWorker(
            SlaTargetQualificationService qualification, TicketSlaProperties properties,
            @Qualifier("ticketSlaTaskScheduler") ThreadPoolTaskScheduler scheduler, Clock clock) {
        properties.validate();
        return new SlaQualificationRefreshWorker(qualification, properties, scheduler, clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "flowdesk.ticket.sla", name = "rebuilder-enabled", havingValue = "true")
    SlaIndexRebuilder slaIndexRebuilder(TicketRepository repository, SlaDueIndex index,
                                        TicketSlaProperties properties,
                                        PlatformTransactionManager transactionManager, Clock clock) {
        properties.validate();
        return new SlaIndexRebuilder(repository, index, properties, transactionManager, clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "flowdesk.ticket.sla", name = "rebuilder-enabled", havingValue = "true")
    SlaRebuildPollingWorker slaRebuildPollingWorker(
            SlaIndexRebuilder rebuilder, TicketSlaProperties properties,
            @Qualifier("ticketSlaTaskScheduler") ThreadPoolTaskScheduler scheduler, Clock clock) {
        return new SlaRebuildPollingWorker(rebuilder, properties, scheduler, clock);
    }
}
