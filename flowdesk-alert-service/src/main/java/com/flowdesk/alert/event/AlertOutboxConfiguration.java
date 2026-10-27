package com.flowdesk.alert.event;
import com.flowdesk.alert.service.impl.AlertOutboxDeliveryServiceImpl;
import com.flowdesk.alert.service.AlertOutboxDeliveryService;
import com.flowdesk.alert.mapper.AlertOutboxMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按发布开关组装 Alert Outbox 仓储、RocketMQ 发布器及后台调度器。
 *
 * <p>生产者随 Bean 初始化启动、销毁时关闭；专用单线程只负责轮询，数据库租约仍负责多个进程之间的竞争。
 * 开关关闭时不创建这条发布链路，已保存的 Outbox 记录留待启用后继续处理。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AlertOutboxProperties.class)
@ConditionalOnProperty(
        prefix = "flowdesk.alert.outbox.publisher",
        name = "enabled",
        havingValue = "true")
class AlertOutboxConfiguration {
    /**
     * 向 Spring 容器提供 AlertOutboxDeliveryServiceImpl 组件，集中绑定持久化、认证配置或后台处理依赖，供对应业务入口复用。
     */
    @Bean
    AlertOutboxDeliveryService alertOutboxDeliveryService(AlertOutboxMapper mapper, PlatformTransactionManager tx) {
        return new AlertOutboxDeliveryServiceImpl(mapper, tx);
    }

    /**
     * 向 Spring 容器提供 AlertOutboxPublisher 组件，集中绑定持久化、认证配置或后台处理依赖，供对应业务入口复用。
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    AlertOutboxPublisher alertOutboxPublisher(
            AlertOutboxDeliveryService r, AlertOutboxProperties p) {
        return new AlertOutboxPublisher(r, p);
    }

    /**
     * 向 Spring 容器提供 ThreadPoolTaskScheduler 组件，集中绑定持久化、认证配置或后台处理依赖，供对应业务入口复用。
     */
    @Bean(name = "alertOutboxScheduler")
    ThreadPoolTaskScheduler alertOutboxScheduler() {
        var s = new ThreadPoolTaskScheduler();
        s.setPoolSize(1);
        s.setThreadNamePrefix("alert-outbox-");
        s.setWaitForTasksToCompleteOnShutdown(true);
        return s;
    }

    /**
     * 向 Spring 容器提供 AlertOutboxWorker 组件，集中绑定持久化、认证配置或后台处理依赖，供对应业务入口复用。
     */
    @Bean
    AlertOutboxWorker alertOutboxWorker(
            AlertOutboxPublisher p, @Qualifier("alertOutboxScheduler") TaskScheduler s) {
        return new AlertOutboxWorker(p, s);
    }

    /**
     * 管理轮询任务的启动与停止；每轮结束后才安排下一轮，避免同一工作线程重叠调用发布器。
     * 有候选时立即续扫，无候选或整轮异常时等待五百毫秒；单条消息的失败退避由数据库时间控制。
     */
    static final class AlertOutboxWorker implements SmartLifecycle {
        private static final Logger LOGGER = LoggerFactory.getLogger(AlertOutboxWorker.class);
        private final AlertOutboxPublisher publisher;
        private final TaskScheduler scheduler;
        private final AtomicBoolean running = new AtomicBoolean();
        private volatile ScheduledFuture<?> task;

        /**
         * 保存消息发布器、周期协调器与任务调度器，在生命周期启动时登记定期扫描任务。
         */
        AlertOutboxWorker(AlertOutboxPublisher p, TaskScheduler s) {
            publisher = p;
            scheduler = s;
        }

        /**
         * 按已启用配置启动后台调度或消息订阅，成功建立处理资源后更新运行状态。
         */
        public synchronized void start() {
            if (running.compareAndSet(false, true)) {
                schedule(Instant.now());
                LOGGER.info("Incident Alert Outbox publishers are ready");
            }
        }

        /**
         * 登记一个有界后台扫描任务，定期发现待发送或待协调数据并交由既有业务能力处理。
         */
        private void schedule(Instant at) {
            if (running.get())
                task =
                        scheduler.schedule(
                                () -> {
                                    long delay = 500;
                                    try {
                                        var r = publisher.publishOnce();
                                        delay = r.scanned() == 0 ? 500 : 0;
                                    } catch (RuntimeException failure) {
                                        LOGGER.warn(
                                                "Alert Outbox poll failed; retrying after the fixed empty-poll interval");
                                    }
                                    schedule(Instant.now().plusMillis(delay));
                                },
                                at);
        }

        /** 停止续排并取消尚未开始的任务，不强行打断正在执行的发送或结算。 */
        public synchronized void stop() {
            running.set(false);
            if (task != null) task.cancel(false);
        }

        /**
         * 返回组件当前运行标志，供 Spring 生命周期协调启动和停止。
         */
        public boolean isRunning() {
            return running.get();
        }

        /**
         * 声明该后台组件是否随 Spring 上下文自动启动，避免依赖手工触发。
         */
        public boolean isAutoStartup() {
            return true;
        }

        /**
         * 返回生命周期阶段，供 Spring 确定消息消费者、调度器与依赖资源的启停顺序。
         */
        public int getPhase() {
            return Integer.MAX_VALUE - 90;
        }
    }
}
