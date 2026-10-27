package com.flowdesk.common.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 恢复已提交、但因进程中断而丢失本地执行任务的 Inbox 人工重放。
 * REPLAYING 状态下的 next_attempt_at 表示执行租约；接管任务时保留原人工操作和审计身份。
 */
public final class RecoveryReplayResumer implements SmartLifecycle {
    public static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    private static final Logger LOG = LoggerFactory.getLogger(RecoveryReplayResumer.class);

    private final RecoveryReplayMapper mapper;
    private final TransactionTemplate transactions;
    private final Consumer<Task> handler;
    private final boolean enabled;
    private volatile boolean running;
    private ScheduledExecutorService scheduler;

    /**
     * 注入恢复 Mapper、事务管理器及任务提交回调，按配置决定是否启用定期租约接管。
     */
    public RecoveryReplayResumer(RecoveryReplayMapper mapper, PlatformTransactionManager transactionManager,
                                    Consumer<Task> handler, boolean enabled) {
        this.mapper = Objects.requireNonNull(mapper);
        this.transactions = new TransactionTemplate(transactionManager);
        this.handler = Objects.requireNonNull(handler);
        this.enabled = enabled;
    }

    /**
     * 根据当前时间推算租约截止点，并截断到微秒以匹配数据库比较精度。
     */
    public static Instant leaseUntil(Instant now) {
        return now.plus(LEASE_DURATION).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 扫描租约到期的人工重放任务，以版本和原租约条件竞争接管权，再在领取事务提交后调用处理器。
     * 提交回调失败时保留已提交租约，待其再次过期后重试；此入口也可由测试或运维显式调用。
     *
     * @param now 本轮扫描采用的时间，统一截断至数据库使用的微秒精度
     * @return 成功接管的任务数，包含已经领取但提交回调抛出异常的任务
     */
    public int resumeDue(Instant now) {
        Instant at = now.truncatedTo(ChronoUnit.MICROS);
        // 只扫描持久化的人工重放意图，进程重启后继续同一次操作，避免生成重复审计。
        List<RecoveryReplayCandidate> candidates = mapper.findDue(at);
        int resumed = 0;
        for (RecoveryReplayCandidate candidate : candidates) {
            Instant until = leaseUntil(at);
            // 以版本号和租约截止时间共同争抢执行权，确保多个实例不会同时接管同一任务。
            Boolean claimed = transactions.execute(tx -> mapper.claim(candidate, until, at) == 1);
            if (!Boolean.TRUE.equals(claimed)) continue;
            resumed++;
            try {
                // 先提交租约再执行处理器；后续失败不会使任务失去可恢复的持久化依据。
                handler.accept(new Task(candidate.tenantId(), candidate.recoveryId(),
                        candidate.sourceRecordId(), candidate.actorId(), candidate.reason(),
                        candidate.attemptCorrelationId(), candidate.sourceCorrelationId(), until));
            } catch (RuntimeException unavailable) {
                // 即使任务提交或结算失败，已提交的租约仍可在过期后被重新接管。
                LOG.warn("Recovery replay submission failed; its persisted lease will expire", unavailable);
            }
        }
        return resumed;
    }

    /**
     * 启用时创建守护调度器，每五秒扫描到期任务；重复启动无效，单次扫描异常留待下一周期重试。
     */
    @Override
    public synchronized void start() {
        if (running || !enabled) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "flowdesk-recovery-resumer");
            thread.setDaemon(true);
            return thread;
        });
        running = true;
        scheduler.scheduleWithFixedDelay(() -> {
            try { resumeDue(Instant.now()); }
            catch (RuntimeException unavailable) {
                LOG.warn("Recovery replay scan failed; a later scan will retry", unavailable);
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * 停止扫描并关闭调度线程，避免应用关闭后继续提交恢复任务。
     */
    @Override
    public synchronized void stop() {
        running = false;
        if (scheduler != null) scheduler.shutdownNow();
    }

    /**
     * 返回扫描器的运行标记，供 Spring 生命周期管理判断当前状态。
     */
    @Override public boolean isRunning() { return running; }
    /**
     * 根据配置决定是否随应用启动恢复扫描器。
     */
    @Override public boolean isAutoStartup() { return enabled; }
    /**
     * 提供 Spring 生命周期阶段，控制恢复扫描器相对其他组件的启动与关闭顺序。
     */
    @Override public int getPhase() { return Integer.MAX_VALUE; }

    /**
     * 恢复扫描提交给业务执行器的任务快照，沿用持久化身份及人工重放尝试。
     */
    public record Task(UUID tenantId, UUID recoveryId, UUID sourceRecordId, UUID actorId,
                       String reason, UUID attemptCorrelationId, UUID sourceCorrelationId,
                       Instant leaseUntil) { }
}
