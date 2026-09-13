package com.flowdesk.workflow.notification;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkflowNotificationOutboxPublisherProperties.class)
@ConditionalOnProperty(prefix = "flowdesk.workflow.notification.outbox.publisher",
        name = "enabled", havingValue = "true")
public class WorkflowNotificationOutboxPublisherConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    RocketMqWorkflowNotificationOutboxTransport workflowNotificationOutboxTransport(
            WorkflowNotificationOutboxPublisherProperties properties) {
        return RocketMqWorkflowNotificationOutboxTransport.create(properties);
    }
    @Bean WorkflowNotificationOutboxPublisher workflowNotificationOutboxPublisher(
            WorkflowNotificationOutboxRepository repository,
            RocketMqWorkflowNotificationOutboxTransport transport,
            PlatformTransactionManager manager, Clock clock) {
        return new WorkflowNotificationOutboxPublisher(repository, transport, manager, clock);
    }
    @Bean(name = "workflowNotificationOutboxScheduler") ThreadPoolTaskScheduler workflowNotificationOutboxScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("workflow-notification-outbox-"); scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true); scheduler.setAwaitTerminationSeconds(10); return scheduler;
    }
    @Bean WorkflowNotificationOutboxPollingWorker workflowNotificationOutboxPollingWorker(
            WorkflowNotificationOutboxPublisher publisher,
            @Qualifier("workflowNotificationOutboxScheduler") ThreadPoolTaskScheduler scheduler,
            Clock clock) { return new WorkflowNotificationOutboxPollingWorker(publisher, scheduler, clock); }
}
