package com.flowdesk.ticket.reverse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.flowdesk.ticket.notification.TicketNotificationOutboxWriter;
import com.flowdesk.ticket.sla.SlaIndexProjection;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkflowReverseRocketMqProperties.class)
@ConditionalOnProperty(prefix = "flowdesk.ticket.workflow-reverse.entry", name = "enabled",
        havingValue = "true")
public class WorkflowReverseConfiguration {
    @Bean WorkflowReverseEnvelopeGuard workflowReverseEnvelopeGuard() {
        return new WorkflowReverseEnvelopeGuard();
    }
    @Bean WorkflowReverseInboxRepository workflowReverseInboxRepository(JdbcTemplate jdbc) {
        return new WorkflowReverseInboxRepository(jdbc);
    }
    @Bean WorkflowReverseConsumer workflowReverseConsumer(
            WorkflowReverseEnvelopeGuard guard, WorkflowReverseInboxRepository repository,
            PlatformTransactionManager transactionManager, Clock clock,
            TicketNotificationOutboxWriter notificationWriter, SlaIndexProjection slaIndexProjection) {
        return new WorkflowReverseConsumer(guard, repository, transactionManager, clock,
                notificationWriter, slaIndexProjection);
    }
    @Bean WorkflowReverseDlqAcceptor workflowReverseDlqAcceptor(
            WorkflowReverseEnvelopeGuard guard, WorkflowReverseInboxRepository repository,
            PlatformTransactionManager transactionManager) {
        return new WorkflowReverseDlqAcceptor(guard, repository, transactionManager);
    }
    @Bean
    @ConditionalOnProperty(prefix = "flowdesk.ticket.workflow-reverse.consumer",
            name = "enabled", havingValue = "true")
    WorkflowReverseRocketMqConsumers workflowReverseRocketMqConsumers(
            WorkflowReverseRocketMqProperties properties, WorkflowReverseConsumer main,
            WorkflowReverseDlqAcceptor dlq) {
        return new WorkflowReverseRocketMqConsumers(properties, main, dlq);
    }
}
