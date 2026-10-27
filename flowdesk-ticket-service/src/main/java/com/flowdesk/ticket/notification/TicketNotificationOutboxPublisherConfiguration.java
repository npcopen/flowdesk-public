package com.flowdesk.ticket.notification;

import org.springframework.beans.factory.annotation.Qualifier;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.boot.context.properties.EnableConfigurationProperties;import org.springframework.context.annotation.Bean;import org.springframework.context.annotation.Configuration;import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;

/** 按部署开关装配工单通知发布器、传输适配器和调度器。 */
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(TicketNotificationOutboxPublisherProperties.class)
@ConditionalOnProperty(prefix="flowdesk.ticket.notification.outbox.publisher",name="enabled",havingValue="true")
public class TicketNotificationOutboxPublisherConfiguration{
    /**
     * 装配工单通知的 ticketNotificationOutboxTransport 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean(initMethod="start",destroyMethod="close") RocketMqTicketNotificationOutboxTransport ticketNotificationOutboxTransport(TicketNotificationOutboxPublisherProperties p){return RocketMqTicketNotificationOutboxTransport.create(p);}
    /**
     * 装配工单通知的 ticketNotificationOutboxPublisher 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean TicketNotificationOutboxPublisher ticketNotificationOutboxPublisher(TicketNotificationDispatchService r,RocketMqTicketNotificationOutboxTransport t,PlatformTransactionManager m,Clock c){return new TicketNotificationOutboxPublisher(r,t,m,c);}
    /**
     * 装配工单通知的 ticketNotificationOutboxScheduler 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean(name="ticketNotificationOutboxScheduler") ThreadPoolTaskScheduler ticketNotificationOutboxScheduler(){ThreadPoolTaskScheduler s=new ThreadPoolTaskScheduler();s.setPoolSize(1);s.setThreadNamePrefix("ticket-notification-outbox-");s.setRemoveOnCancelPolicy(true);s.setWaitForTasksToCompleteOnShutdown(true);s.setAwaitTerminationSeconds(10);return s;}
    /**
     * 装配工单通知的 ticketNotificationOutboxPollingWorker 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean TicketNotificationOutboxPollingWorker ticketNotificationOutboxPollingWorker(TicketNotificationOutboxPublisher p,@Qualifier("ticketNotificationOutboxScheduler")ThreadPoolTaskScheduler s,Clock c){return new TicketNotificationOutboxPollingWorker(p,s,c);}
}
