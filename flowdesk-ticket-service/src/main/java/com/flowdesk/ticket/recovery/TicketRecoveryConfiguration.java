package com.flowdesk.ticket.recovery;

import com.flowdesk.ticket.reverse.WorkflowReverseConsumer;
import com.flowdesk.ticket.incident.IncidentMessageConsumer;
import com.flowdesk.ticket.service.TicketCursorTokenService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${flowdesk.ticket.workflow-reverse.entry.enabled:false} or ${flowdesk.ticket.incident-entry.enabled:false}")
public class TicketRecoveryConfiguration {
    @Bean TicketRecoveryRepository ticketRecoveryRepository(JdbcTemplate jdbc) {
        return new TicketRecoveryRepository(jdbc);
    }
    @Bean(name = "ticketRecoveryExecutor") ThreadPoolTaskExecutor ticketRecoveryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ticket-recovery-");
        executor.setWaitForTasksToCompleteOnShutdown(true); executor.setAwaitTerminationSeconds(10);
        return executor;
    }
    @Bean TicketRecoveryReplayWorker ticketRecoveryReplayWorker(
            @Qualifier("ticketRecoveryExecutor") ThreadPoolTaskExecutor executor,
            TicketRecoveryRepository repository,
            ObjectProvider<WorkflowReverseConsumer> workflowConsumer,
            ObjectProvider<IncidentMessageConsumer> incidentConsumer,
            PlatformTransactionManager transactionManager) {
        return new TicketRecoveryReplayWorker(executor, repository,
                workflowConsumer.getIfAvailable(), incidentConsumer.getIfAvailable(), transactionManager);
    }
    @Bean TicketRecoveryService ticketRecoveryService(
            TicketRecoveryRepository repository, TicketCursorTokenService cursors,
            PlatformTransactionManager transactionManager, TicketRecoveryReplayWorker worker) {
        return new TicketRecoveryService(repository, cursors, transactionManager, worker);
    }
}
