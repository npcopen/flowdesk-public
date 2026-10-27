package com.flowdesk.ticket.recovery;

import com.flowdesk.ticket.recovery.impl.TicketRecoveryReplayWorker;
import com.flowdesk.ticket.recovery.impl.TicketRecoveryServiceImpl;
import com.flowdesk.ticket.reverse.WorkflowReverseConsumer;
import com.flowdesk.ticket.incident.IncidentMessageConsumer;
import com.flowdesk.ticket.service.TicketCursorTokenService;
import com.flowdesk.common.recovery.RecoveryReplayResumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/** 在已启用的消息消费入口下装配工单恢复服务、专用执行器和持久重放任务续跑器。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${flowdesk.ticket.workflow-reverse.entry.enabled:false} or ${flowdesk.ticket.incident-entry.enabled:false}")
public class TicketRecoveryConfiguration {
    /**
     * 装配消息恢复的 ticketRecoveryExecutor 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean(name = "ticketRecoveryExecutor") ThreadPoolTaskExecutor ticketRecoveryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ticket-recovery-");
        executor.setWaitForTasksToCompleteOnShutdown(true); executor.setAwaitTerminationSeconds(10);
        return executor;
    }
    /**
     * 装配消息恢复的 ticketRecoveryReplayWorker 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean TicketRecoveryReplayService ticketRecoveryReplayWorker(
            @Qualifier("ticketRecoveryExecutor") ThreadPoolTaskExecutor executor,
            TicketRecoveryPersistenceMapper mapper,
            ObjectProvider<WorkflowReverseConsumer> workflowConsumer,
            ObjectProvider<IncidentMessageConsumer> incidentConsumer,
            PlatformTransactionManager transactionManager) {
        return new TicketRecoveryReplayWorker(executor, mapper,
                workflowConsumer.getIfAvailable(), incidentConsumer.getIfAvailable(), transactionManager);
    }
    /**
     * 装配消息恢复的 ticketRecoveryService 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean TicketRecoveryService ticketRecoveryService(
            TicketRecoveryPersistenceMapper mapper, TicketCursorTokenService cursors,
            PlatformTransactionManager transactionManager, TicketRecoveryReplayService worker) {
        return new TicketRecoveryServiceImpl(mapper, cursors, transactionManager, worker);
    }
    /**
     * 装配消息恢复的 ticketRecoveryReplayResumer 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean RecoveryReplayResumer ticketRecoveryReplayResumer(
            TicketRecoveryReplayMapper mapper, PlatformTransactionManager transactionManager,
            TicketRecoveryReplayService worker,
            @Value("${flowdesk.recovery.resume-enabled:true}") boolean enabled) {
        return new RecoveryReplayResumer(mapper, transactionManager, worker::resume, enabled);
    }
}
