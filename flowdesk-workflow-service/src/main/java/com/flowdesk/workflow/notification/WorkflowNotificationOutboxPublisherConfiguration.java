package com.flowdesk.workflow.notification;

import com.flowdesk.workflow.service.WorkflowNotificationOutboxService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;

/**
 * 按配置装配审批通知发布器、发送适配器及轮询生命周期。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkflowNotificationOutboxPublisherProperties.class)
@ConditionalOnProperty(prefix = "flowdesk.workflow.notification.outbox.publisher",
        name = "enabled", havingValue = "true")
public class WorkflowNotificationOutboxPublisherConfiguration {
    /**
     * 装配消息传输适配器，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    RocketMqWorkflowNotificationOutboxTransport workflowNotificationOutboxTransport(
            WorkflowNotificationOutboxPublisherProperties properties) {
        return RocketMqWorkflowNotificationOutboxTransport.create(properties);
    }
    /**
     * 装配消息发布器，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean WorkflowNotificationOutboxPublisher workflowNotificationOutboxPublisher(
            WorkflowNotificationOutboxService service,
            RocketMqWorkflowNotificationOutboxTransport transport,
            Clock clock) {
        return new WorkflowNotificationOutboxPublisher(service, transport, clock);
    }
    /**
     * 装配后台调度器，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean(name = "workflowNotificationOutboxScheduler") ThreadPoolTaskScheduler workflowNotificationOutboxScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("workflow-notification-outbox-"); scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true); scheduler.setAwaitTerminationSeconds(10); return scheduler;
    }
    /**
     * 装配后台工作者，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean WorkflowNotificationOutboxPollingWorker workflowNotificationOutboxPollingWorker(
            WorkflowNotificationOutboxPublisher publisher,
            @Qualifier("workflowNotificationOutboxScheduler") ThreadPoolTaskScheduler scheduler,
            Clock clock) { return new WorkflowNotificationOutboxPollingWorker(publisher, scheduler, clock); }
}
