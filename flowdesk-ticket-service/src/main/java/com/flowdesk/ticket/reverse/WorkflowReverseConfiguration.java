package com.flowdesk.ticket.reverse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.transaction.PlatformTransactionManager;

import com.flowdesk.ticket.notification.TicketNotificationOutboxWriter;
import com.flowdesk.ticket.sla.SlaIndexProjection;
import java.time.Clock;

/** 按入口与消费者开关装配流程反向事件校验、Inbox、消费及死信恢复组件。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkflowReverseRocketMqProperties.class)
@ConditionalOnProperty(prefix = "flowdesk.ticket.workflow-reverse.entry", name = "enabled",
        havingValue = "true")
public class WorkflowReverseConfiguration {
    /**
     * 装配工作流反向结果的 workflowReverseEnvelopeGuard 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean WorkflowReverseEnvelopeGuard workflowReverseEnvelopeGuard() {
        return new WorkflowReverseEnvelopeGuard();
    }
    /**
     * 装配工作流反向结果的 workflowReverseInboxService 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean WorkflowReverseInboxService workflowReverseInboxService(WorkflowReverseInboxPersistenceMapper mapper) {
        return new com.flowdesk.ticket.reverse.impl.WorkflowReverseInboxServiceImpl(mapper);
    }
    /**
     * 装配工作流反向结果的 workflowReverseConsumer 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean WorkflowReverseConsumer workflowReverseConsumer(
            WorkflowReverseEnvelopeGuard guard, WorkflowReverseInboxService repository,
            PlatformTransactionManager transactionManager, Clock clock,
            TicketNotificationOutboxWriter notificationWriter, SlaIndexProjection slaIndexProjection) {
        return new WorkflowReverseConsumer(guard, repository, transactionManager, clock,
                notificationWriter, slaIndexProjection);
    }
    /**
     * 装配工作流反向结果的 workflowReverseDlqAcceptor 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean WorkflowReverseDlqAcceptor workflowReverseDlqAcceptor(
            WorkflowReverseEnvelopeGuard guard, WorkflowReverseInboxService repository,
            PlatformTransactionManager transactionManager) {
        return new WorkflowReverseDlqAcceptor(guard, repository, transactionManager);
    }
    /**
     * 装配工作流反向结果的 workflowReverseRocketMqConsumers 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean
    @ConditionalOnProperty(prefix = "flowdesk.ticket.workflow-reverse.consumer",
            name = "enabled", havingValue = "true")
    WorkflowReverseRocketMqConsumers workflowReverseRocketMqConsumers(
            WorkflowReverseRocketMqProperties properties, WorkflowReverseConsumer main,
            WorkflowReverseDlqAcceptor dlq) {
        return new WorkflowReverseRocketMqConsumers(properties, main, dlq);
    }
}
