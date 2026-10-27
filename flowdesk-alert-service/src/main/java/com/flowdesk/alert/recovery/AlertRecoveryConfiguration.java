package com.flowdesk.alert.recovery;
import com.flowdesk.alert.service.*;
import com.flowdesk.alert.service.impl.*;

import com.flowdesk.common.recovery.RecoveryReplayResumer;
import org.springframework.beans.factory.annotation.Value;
import com.flowdesk.alert.event.TicketResultConsumer;
import com.flowdesk.alert.service.AlertCursorTokenService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.flowdesk.alert.mapper.AlertRecoveryMapper;
import com.flowdesk.alert.mapper.AlertRecoveryReplayMapper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 组装告警人工恢复链路：Controller 使用 Service 接纳命令，Worker 复用原工单结果消费者执行 Inbox 重放。
 *
 * <p>本地执行器负责即时执行，公共 RecoveryReplayResumer 则扫描已过期的持久化租约，
 * 补上进程退出或本地任务丢失造成的执行空档；两条入口最终提交相同的 Work。
 * 本配置随工单结果入口一起启用，后台接管还可由 recovery.resume-enabled 单独暂停。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "flowdesk.alert.ticket-result.entry",
        name = "enabled",
        havingValue = "true")
class AlertRecoveryConfiguration {
    /**
     * 将公共扫描器领取到的任务转成 Alert 工作凭据。接管保留原操作者、原因和人工尝试标识，
     * 只更新执行租约，因此不会增加人工重放次数，也不会替操作员接纳新的尝试。
     */
    @Bean
    RecoveryReplayResumer alertRecoveryReplayResumer(
            AlertRecoveryReplayMapper mapper,
            PlatformTransactionManager tx,
            AlertRecoveryExecutionService worker,
            @Value("${flowdesk.recovery.resume-enabled:true}") boolean enabled) {
        return new RecoveryReplayResumer(
                mapper,
                tx,
                t ->
                        worker.submit(
                                new AlertRecoveryService.Work(
                                        t.tenantId(),
                                        t.recoveryId(),
                                        t.sourceRecordId(),
                                        t.actorId(),
                                        t.reason(),
                                        t.attemptCorrelationId(),
                                        t.sourceCorrelationId(),
                                        t.leaseUntil())),
                enabled);
    }

/** 使用独立、单线程且容量有限的队列隔离人工重放；队列满时由 Worker 记录提交失败。 */
    @Bean(name = "alertRecoveryExecutor")
    ThreadPoolTaskExecutor alertRecoveryExecutor() {
        var e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(1);
        e.setMaxPoolSize(1);
        e.setQueueCapacity(100);
        e.setThreadNamePrefix("alert-recovery-");
        e.setWaitForTasksToCompleteOnShutdown(true);
        return e;
    }

    /**
     * 向 Spring 容器提供 AlertRecoveryExecutionServiceImpl 组件，集中绑定持久化、认证配置或后台处理依赖，供对应业务入口复用。
     */
    @Bean
    AlertRecoveryExecutionService alertRecoveryWorker(
            @Qualifier("alertRecoveryExecutor") ThreadPoolTaskExecutor e,
            AlertRecoveryMapper r,
            TicketResultConsumer c,
            PlatformTransactionManager tx) {
        return new AlertRecoveryExecutionServiceImpl(e, r, c, tx);
    }

    /**
     * 向 Spring 容器提供 AlertRecoveryServiceImpl 组件，集中绑定持久化、认证配置或后台处理依赖，供对应业务入口复用。
     */
    @Bean
    AlertRecoveryService alertRecoveryService(
            AlertRecoveryMapper r,
            AlertCursorTokenService c,
            PlatformTransactionManager tx,
            AlertRecoveryExecutionService w) {
        return new AlertRecoveryServiceImpl(r, tx, w);
    }
    /**
     * 向 Spring 容器提供 AlertRecoveryQueryServiceImpl 组件，集中绑定持久化、认证配置或后台处理依赖，供对应业务入口复用。
     */
    @Bean
    AlertRecoveryQueryService alertRecoveryQueryService(AlertRecoveryMapper mapper, AlertCursorTokenService cursors) {
        return new AlertRecoveryQueryServiceImpl(mapper, cursors);
    }
}
