package com.flowdesk.ticket.notification;

import org.springframework.beans.factory.annotation.Qualifier;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.boot.context.properties.EnableConfigurationProperties;import org.springframework.context.annotation.Bean;import org.springframework.context.annotation.Configuration;import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(TicketNotificationOutboxPublisherProperties.class)
@ConditionalOnProperty(prefix="flowdesk.ticket.notification.outbox.publisher",name="enabled",havingValue="true")
public class TicketNotificationOutboxPublisherConfiguration{
    @Bean(initMethod="start",destroyMethod="close") RocketMqTicketNotificationOutboxTransport ticketNotificationOutboxTransport(TicketNotificationOutboxPublisherProperties p){return RocketMqTicketNotificationOutboxTransport.create(p);}
    @Bean TicketNotificationOutboxPublisher ticketNotificationOutboxPublisher(TicketNotificationOutboxRepository r,RocketMqTicketNotificationOutboxTransport t,PlatformTransactionManager m,Clock c){return new TicketNotificationOutboxPublisher(r,t,m,c);}
    @Bean(name="ticketNotificationOutboxScheduler") ThreadPoolTaskScheduler ticketNotificationOutboxScheduler(){ThreadPoolTaskScheduler s=new ThreadPoolTaskScheduler();s.setPoolSize(1);s.setThreadNamePrefix("ticket-notification-outbox-");s.setRemoveOnCancelPolicy(true);s.setWaitForTasksToCompleteOnShutdown(true);s.setAwaitTerminationSeconds(10);return s;}
    @Bean TicketNotificationOutboxPollingWorker ticketNotificationOutboxPollingWorker(TicketNotificationOutboxPublisher p,@Qualifier("ticketNotificationOutboxScheduler")ThreadPoolTaskScheduler s,Clock c){return new TicketNotificationOutboxPollingWorker(p,s,c);}
}
