package com.flowdesk.ticket.sla;

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

/** M8 SLA 扫描与索引的组件装配，所有后台工作器均需显式配置启用。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TicketSlaProperties.class)
public class TicketSlaConfiguration {

    /**
     * 装配SLA的 redisSlaDueIndex 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean
    @ConditionalOnProperty(prefix = "flowdesk.ticket.sla", name = "redis-enabled", havingValue = "true")
    RedisSlaDueIndex redisSlaDueIndex(StringRedisTemplate redis) {
        return new RedisSlaDueIndex(redis);
    }

    /**
     * 装配SLA的 noopSlaDueIndex 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean
    @ConditionalOnMissingBean(SlaDueIndex.class)
    NoopSlaDueIndex noopSlaDueIndex() {
        return new NoopSlaDueIndex();
    }

    /**
     * 装配SLA的 slaIndexProjection 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean
    SlaIndexProjection slaIndexProjection(SlaPersistenceMapper repository,
                                          SlaRebuildLeaseService leaseRepository,
                                          SlaDueIndex index, Clock clock) {
        return new SlaIndexProjection(repository, leaseRepository, index, clock);
    }

    /**
     * 装配SLA的 slaTargetConfigurationGate 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean
    SlaTargetConfigurationGate slaTargetConfigurationGate(
            SlaTargetPersistenceMapper mapper, SlaTargetQualificationService qualification, Clock clock) {
        return new SlaTargetConfigurationGate(mapper, qualification, clock);
    }

    /**
     * 装配SLA的 ticketSlaTaskScheduler 组件，将配置与依赖交由 Spring 管理。
     */
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

    /**
     * 装配SLA的 ticketSlaScanner 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean
    @ConditionalOnProperty(prefix = "flowdesk.ticket.sla", name = "scanner-enabled", havingValue = "true")
    TicketSlaScanner ticketSlaScanner(SlaPersistenceMapper repository,
                                     SlaRebuildLeaseService leaseRepository,
                                     TicketSlaService slaService, SlaDueIndex index,
                                     TicketSlaProperties properties,
                                     Clock clock) {
        properties.validate();
        return new TicketSlaScanner(repository, leaseRepository, slaService, index, properties, clock);
    }

    /**
     * 装配SLA的 ticketSlaPollingWorker 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean
    @ConditionalOnProperty(prefix = "flowdesk.ticket.sla", name = "scanner-enabled", havingValue = "true")
    TicketSlaPollingWorker ticketSlaPollingWorker(
            TicketSlaScanner scanner, TicketSlaProperties properties,
            @Qualifier("ticketSlaTaskScheduler") ThreadPoolTaskScheduler scheduler, Clock clock) {
        return new TicketSlaPollingWorker(scanner, properties, scheduler, clock);
    }

    /**
     * 装配SLA的 slaQualificationRefreshWorker 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean
    @ConditionalOnProperty(prefix = "flowdesk.ticket.sla", name = "enabled", havingValue = "true")
    SlaQualificationRefreshWorker slaQualificationRefreshWorker(
            SlaTargetQualificationService qualification, TicketSlaProperties properties,
            @Qualifier("ticketSlaTaskScheduler") ThreadPoolTaskScheduler scheduler, Clock clock) {
        properties.validate();
        return new SlaQualificationRefreshWorker(qualification, properties, scheduler, clock);
    }

    /**
     * 装配SLA的 slaIndexRebuilder 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean
    @ConditionalOnProperty(prefix = "flowdesk.ticket.sla", name = "rebuilder-enabled", havingValue = "true")
    SlaIndexRebuilder slaIndexRebuilder(SlaPersistenceMapper repository,
                                        SlaRebuildLeaseService leaseRepository,
                                        SlaDueIndex index, TicketSlaProperties properties,
                                        PlatformTransactionManager transactionManager, Clock clock) {
        properties.validate();
        return new SlaIndexRebuilder(repository, leaseRepository, index, properties, transactionManager, clock);
    }

    /**
     * 装配SLA的 slaRebuildPollingWorker 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean
    @ConditionalOnProperty(prefix = "flowdesk.ticket.sla", name = "rebuilder-enabled", havingValue = "true")
    SlaRebuildPollingWorker slaRebuildPollingWorker(
            SlaIndexRebuilder rebuilder, TicketSlaProperties properties,
            @Qualifier("ticketSlaTaskScheduler") ThreadPoolTaskScheduler scheduler, Clock clock) {
        return new SlaRebuildPollingWorker(rebuilder, properties, scheduler, clock);
    }
}
