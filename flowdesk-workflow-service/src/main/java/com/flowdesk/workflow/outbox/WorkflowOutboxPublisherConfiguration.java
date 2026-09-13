package com.flowdesk.workflow.outbox;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkflowOutboxPublisherProperties.class)
@ConditionalOnProperty(prefix = "flowdesk.workflow.outbox.publisher", name = "enabled",
        havingValue = "true")
public class WorkflowOutboxPublisherConfiguration {
    @Bean WorkflowOutboxRepository workflowOutboxRepository(JdbcTemplate jdbc) {
        return new WorkflowOutboxRepository(jdbc);
    }
    @Bean(initMethod = "start", destroyMethod = "close")
    RocketMqWorkflowOutboxTransport workflowOutboxTransport(
            WorkflowOutboxPublisherProperties properties) {
        return RocketMqWorkflowOutboxTransport.create(properties);
    }
    @Bean WorkflowOutboxPublisher workflowOutboxPublisher(
            WorkflowOutboxRepository repository, RocketMqWorkflowOutboxTransport transport,
            PlatformTransactionManager transactionManager) {
        return new WorkflowOutboxPublisher(repository, transport, transactionManager);
    }
    @Bean(name = "workflowOutboxScheduler")
    ThreadPoolTaskScheduler workflowOutboxScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("workflow-outbox-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
    @Bean WorkflowOutboxPollingWorker workflowOutboxPollingWorker(
            WorkflowOutboxPublisher publisher,
            @Qualifier("workflowOutboxScheduler") TaskScheduler scheduler) {
        return new WorkflowOutboxPollingWorker(publisher, scheduler);
    }
}
