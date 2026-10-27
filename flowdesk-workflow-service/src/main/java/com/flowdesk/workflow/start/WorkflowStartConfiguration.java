package com.flowdesk.workflow.start;

import com.flowdesk.workflow.service.recovery.*;
import com.flowdesk.workflow.service.start.*;
import com.flowdesk.workflow.service.start.impl.*;
import com.flowdesk.workflow.service.recovery.impl.*;
import com.flowdesk.workflow.service.WorkflowCursorTokenService;
import com.flowdesk.common.recovery.RecoveryReplayResumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import com.flowdesk.workflow.notification.WorkflowNotificationOutboxWriter;

import java.time.Clock;

/**
 * 装配按需启用的严格启动消费入口、独立消息组与受控重放及中断恢复机制。
 */
@Configuration(proxyBeanMethods = false)
@EnableFeignClients(clients = WorkflowIamEligibilityFeignClient.class)
@EnableConfigurationProperties({
        WorkflowIamClientProperties.class,
        WorkflowStartRocketMqProperties.class,
        WorkflowCursorTokenService.CursorProperties.class
})
public class WorkflowStartConfiguration {

    /**
     * 工作流业务时间统一使用同一 UTC 时钟，并对齐 TIMESTAMP(6) 的微秒精度。
     */
    @Bean
    Clock flowDeskWorkflowClock() {
        return Clock.systemUTC();
    }

    /**
     * 装配审批资格检查器，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean
    WorkflowApproverEligibilityChecker workflowApproverEligibilityChecker(
            WorkflowIamEligibilityFeignClient client,
            WorkflowIamClientProperties properties) {
        return new WorkflowIamEligibilityClient(client, properties);
    }

    /**
     * 启动入口启用时的组件装配，集中配置消息消费、事务服务和恢复后台执行器。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
            prefix = "flowdesk.workflow.start.entry",
            name = "enabled",
            havingValue = "true")
    static class EntryConfiguration {

        /**
         * 装配消息或迁移校验器，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
         */
        @Bean
        WorkflowStartMigrationGuard workflowStartMigrationGuard(WorkflowStartMapper mapper) {
            return new WorkflowStartMigrationGuard(mapper);
        }

        /**
         * 装配消息或迁移校验器，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
         */
        @Bean
        WorkflowStartEnvelopeGuard workflowStartEnvelopeGuard() {
            return new WorkflowStartEnvelopeGuard();
        }

        /**
         * 装配业务服务，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
         */
        @Bean
        WorkflowStartQueryService workflowStartQueryService(WorkflowStartMapper mapper) {
            return new WorkflowStartQueryServiceImpl(mapper);
        }

        /**
         * 装配业务服务，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
         */
        @Bean
        WorkflowStartCommandService workflowStartCommandService(WorkflowStartMapper mapper,
                PlatformTransactionManager transactionManager, Clock clock,
                WorkflowNotificationOutboxWriter notificationWriter) {
            return new WorkflowStartCommandServiceImpl(mapper, transactionManager, clock, notificationWriter);
        }

        /**
         * 装配业务服务，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
         */
        @Bean
        WorkflowStartDlqService workflowStartDlqService(WorkflowStartMapper mapper,
                PlatformTransactionManager transactionManager) {
            return new WorkflowStartDlqServiceImpl(mapper, transactionManager);
        }

        /**
         * 装配依赖组件，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
         */
        @Bean
        WorkflowStartConsumer workflowStartConsumer(WorkflowStartEnvelopeGuard guard,
                WorkflowStartQueryService queries, WorkflowStartCommandService commands,
                WorkflowApproverEligibilityChecker eligibilityChecker) {
            return new WorkflowStartConsumer(guard, queries, commands, eligibilityChecker);
        }

        /**
         * 装配依赖组件，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
         */
        @Bean
        WorkflowStartDlqAcceptor workflowStartDlqAcceptor(WorkflowStartEnvelopeGuard guard,
                WorkflowStartDlqService service) {
            return new WorkflowStartDlqAcceptor(guard, service);
        }

        /**
         * 装配业务服务，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
         */
        @Bean
        WorkflowRecoveryReadService workflowRecoveryReadService(WorkflowRecoveryMapper mapper,
                WorkflowCursorTokenService cursors) {
            return new WorkflowRecoveryReadServiceImpl(mapper, cursors);
        }

        /**
         * 装配业务服务，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
         */
        @Bean
        WorkflowRecoveryAcceptanceService workflowRecoveryAcceptanceService(WorkflowRecoveryMapper mapper,
                PlatformTransactionManager transactionManager) {
            return new WorkflowRecoveryAcceptanceServiceImpl(mapper, transactionManager);
        }

        /**
         * 装配业务服务，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
         */
        @Bean
        WorkflowRecoveryExecutionService workflowRecoveryExecutionService(WorkflowRecoveryMapper mapper,
                PlatformTransactionManager transactionManager) {
            return new WorkflowRecoveryExecutionServiceImpl(mapper, transactionManager);
        }

        /**
         * 装配异步执行器，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
         */
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

        /**
         * 装配后台工作者，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
         */
        @Bean
        WorkflowRecoveryReplayWorker workflowRecoveryReplayWorker(
                @Qualifier("workflowRecoveryReplayExecutor") ThreadPoolTaskExecutor executor,
                WorkflowStartConsumer consumer,
                WorkflowRecoveryExecutionService settlement) {
            return new WorkflowRecoveryReplayWorker(
                    executor, consumer, settlement);
        }

        /**
         * 装配业务服务，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
         */
        @Bean
        WorkflowRecoveryReplayService workflowRecoveryReplayService(
                WorkflowRecoveryAcceptanceService transaction,
                WorkflowRecoveryReplayWorker worker) {
            return new WorkflowRecoveryReplayServiceImpl(transaction, worker);
        }

        /**
         * 装配重启恢复扫描器，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
         */
        @Bean
        RecoveryReplayResumer workflowRecoveryReplayResumer(
                WorkflowRecoveryReplayMapper mapper, PlatformTransactionManager transactionManager,
                WorkflowRecoveryReplayWorker worker,
                @Value("${flowdesk.recovery.resume-enabled:true}") boolean enabled) {
            return new RecoveryReplayResumer(mapper, transactionManager, worker::resume, enabled);
        }

        /**
         * 装配消息消费者，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
         */
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
