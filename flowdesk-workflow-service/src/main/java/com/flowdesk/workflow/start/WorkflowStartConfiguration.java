package com.flowdesk.workflow.start;

import com.flowdesk.workflow.service.WorkflowCursorTokenService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;
import com.flowdesk.workflow.notification.WorkflowNotificationOutboxWriter;

import java.time.Clock;

/** Production wiring for the opt-in strict entry, MQ groups and controlled replay. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        WorkflowIamClientProperties.class,
        WorkflowStartRocketMqProperties.class,
        WorkflowCursorTokenService.CursorProperties.class
})
public class WorkflowStartConfiguration {

    /** All Workflow business timestamps use one UTC clock and TIMESTAMP(6) precision. */
    @Bean
    Clock flowDeskWorkflowClock() {
        return Clock.systemUTC();
    }

    /** The writer is explicit because it is also used by the opt-in start transaction. */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "flowdesk.workflow.start.entry", name = "enabled",
            havingValue = "false", matchIfMissing = true)
    WorkflowNotificationOutboxWriter workflowNotificationOutboxWriter(
            com.flowdesk.workflow.repository.WorkflowRepository repository) {
        return new WorkflowNotificationOutboxWriter((tenantId, intent) ->
                repository.insertNotificationOutbox(tenantId, intent.outboxId(), intent.messageId(),
                        intent.publicationKey(), intent.businessIntentKey(), intent.sourceRefId(),
                        1, intent.bodyBytes(), intent.bodySha256(), intent.correlationId(),
                        intent.causationId(), intent.occurredAt()));
    }

    @Bean("workflowIamRestClientBuilder")
    RestClient.Builder workflowIamRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    WorkflowApproverEligibilityChecker workflowApproverEligibilityChecker(
            @Qualifier("workflowIamRestClientBuilder") RestClient.Builder builder,
            WorkflowIamClientProperties properties) {
        return new WorkflowIamEligibilityClient(builder, properties);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
            prefix = "flowdesk.workflow.start.entry",
            name = "enabled",
            havingValue = "true")
    static class EntryConfiguration {

        @Bean
        WorkflowNotificationOutboxWriter workflowNotificationOutboxWriter(
                WorkflowStartRepository repository) {
            return new WorkflowNotificationOutboxWriter((tenantId, intent) ->
                    repository.insertNotificationOutbox(tenantId, intent.outboxId(), intent.messageId(),
                            intent.publicationKey(), intent.businessIntentKey(), intent.sourceRefId(),
                            1, intent.bodyBytes(), intent.bodySha256(), intent.correlationId(),
                            intent.causationId(), intent.occurredAt()));
        }

        @Bean
        WorkflowStartMigrationGuard workflowStartMigrationGuard(JdbcTemplate jdbc) {
            return new WorkflowStartMigrationGuard(jdbc);
        }

        @Bean
        WorkflowStartEnvelopeGuard workflowStartEnvelopeGuard() {
            return new WorkflowStartEnvelopeGuard();
        }

        @Bean
        WorkflowStartRepository workflowStartRepository(JdbcTemplate jdbc) {
            return new WorkflowStartRepository(jdbc);
        }

        @Bean
        WorkflowStartTransaction workflowStartTransaction(
                WorkflowStartRepository repository,
                PlatformTransactionManager transactionManager, Clock clock,
                WorkflowNotificationOutboxWriter notificationWriter) {
            return new WorkflowStartTransaction(repository, transactionManager, clock,
                    notificationWriter);
        }

        @Bean
        WorkflowStartDlqTransaction workflowStartDlqTransaction(
                WorkflowStartRepository repository,
                PlatformTransactionManager transactionManager) {
            return new WorkflowStartDlqTransaction(repository, transactionManager);
        }

        @Bean
        WorkflowStartConsumer workflowStartConsumer(
                WorkflowStartEnvelopeGuard guard,
                WorkflowStartRepository repository,
                WorkflowStartTransaction transaction,
                WorkflowApproverEligibilityChecker eligibilityChecker) {
            return new WorkflowStartConsumer(
                    guard, repository, transaction, eligibilityChecker);
        }

        @Bean
        WorkflowStartDlqAcceptor workflowStartDlqAcceptor(
                WorkflowStartEnvelopeGuard guard,
                WorkflowStartDlqTransaction transaction) {
            return new WorkflowStartDlqAcceptor(guard, transaction);
        }

        @Bean
        WorkflowRecoveryRepository workflowRecoveryRepository(JdbcTemplate jdbc) {
            return new WorkflowRecoveryRepository(jdbc);
        }

        @Bean
        WorkflowRecoveryReadService workflowRecoveryReadService(
                WorkflowRecoveryRepository repository,
                WorkflowCursorTokenService cursors) {
            return new WorkflowRecoveryReadService(repository, cursors);
        }

        @Bean
        WorkflowRecoveryReplayTransaction workflowRecoveryReplayTransaction(
                WorkflowRecoveryRepository repository,
                PlatformTransactionManager transactionManager) {
            return new WorkflowRecoveryReplayTransaction(repository, transactionManager);
        }

        @Bean
        WorkflowRecoveryReplaySettlement workflowRecoveryReplaySettlement(
                WorkflowRecoveryRepository repository,
                PlatformTransactionManager transactionManager) {
            return new WorkflowRecoveryReplaySettlement(repository, transactionManager);
        }

        @Bean(name = "workflowRecoveryReplayExecutor")
        ThreadPoolTaskExecutor workflowRecoveryReplayExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(100);
            executor.setThreadNamePrefix("workflow-recovery-replay-");
            executor.setWaitForTasksToCompleteOnShutdown(true);
            executor.setAwaitTerminationSeconds(10);
            return executor;
        }

        @Bean
        WorkflowRecoveryReplayWorker workflowRecoveryReplayWorker(
                @Qualifier("workflowRecoveryReplayExecutor") ThreadPoolTaskExecutor executor,
                WorkflowRecoveryRepository repository,
                WorkflowStartConsumer consumer,
                WorkflowRecoveryReplaySettlement settlement) {
            return new WorkflowRecoveryReplayWorker(
                    executor, repository, consumer, settlement);
        }

        @Bean
        WorkflowRecoveryReplayService workflowRecoveryReplayService(
                WorkflowRecoveryReplayTransaction transaction,
                WorkflowRecoveryReplayWorker worker) {
            return new WorkflowRecoveryReplayService(transaction, worker);
        }

        @Bean
        @ConditionalOnProperty(
                prefix = "flowdesk.workflow.start.consumer",
                name = "enabled",
                havingValue = "true")
        WorkflowStartRocketMqConsumers workflowStartRocketMqConsumers(
                WorkflowStartRocketMqProperties properties,
                WorkflowStartConsumer mainHandler,
                WorkflowStartDlqAcceptor dlqHandler) {
            return new WorkflowStartRocketMqConsumers(properties, mainHandler, dlqHandler);
        }
    }
}
