package com.flowdesk.workflow.outbox;

import com.flowdesk.workflow.service.WorkflowOutboxService;
import com.flowdesk.workflow.service.impl.WorkflowOutboxServiceImpl;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 装配反向事件发布器、RocketMQ 传输实现及后台轮询器。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkflowOutboxPublisherProperties.class)
@ConditionalOnProperty(prefix = "flowdesk.workflow.outbox.publisher", name = "enabled",
        havingValue = "true")
public class WorkflowOutboxPublisherConfiguration {
    /**
     * 装配业务服务，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean WorkflowOutboxService workflowOutboxService(WorkflowOutboxMapper mapper, PlatformTransactionManager manager) {
        return new WorkflowOutboxServiceImpl(mapper, manager);
    }
    /**
     * 装配消息传输适配器，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    RocketMqWorkflowOutboxTransport workflowOutboxTransport(
            WorkflowOutboxPublisherProperties properties) {
        return RocketMqWorkflowOutboxTransport.create(properties);
    }
    /**
     * 装配消息发布器，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean WorkflowOutboxPublisher workflowOutboxPublisher(
            WorkflowOutboxService service, RocketMqWorkflowOutboxTransport transport) {
        return new WorkflowOutboxPublisher(service, transport);
    }
    /**
     * 装配后台调度器，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean(name = "workflowOutboxScheduler")
    ThreadPoolTaskScheduler workflowOutboxScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("workflow-outbox-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
    /**
     * 装配后台工作者，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean WorkflowOutboxPollingWorker workflowOutboxPollingWorker(
            WorkflowOutboxPublisher publisher,
            @Qualifier("workflowOutboxScheduler") TaskScheduler scheduler) {
        return new WorkflowOutboxPollingWorker(publisher, scheduler);
    }
}
