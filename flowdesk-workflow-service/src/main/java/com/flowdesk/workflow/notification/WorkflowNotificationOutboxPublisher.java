package com.flowdesk.workflow.notification;

import com.flowdesk.workflow.model.WorkflowNotificationOutboxModels;
import com.flowdesk.workflow.service.WorkflowNotificationOutboxService;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.tenant.TenantContextScope;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 以有限批次和重试次数发布审批通知；采用至少一次投递语义，接收方需按业务身份去重。
 */
public final class WorkflowNotificationOutboxPublisher {
    static final String RESULT_UNKNOWN = "BROKER_RESULT_UNKNOWN";
    static final String INVALID_CONTRACT = "OUTBOX_CONTRACT_INVALID";
    private final WorkflowNotificationOutboxService service;
    private final WorkflowNotificationOutboxTransport transport;
    private final Clock clock;

    /**
     * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
     */
    public WorkflowNotificationOutboxPublisher(WorkflowNotificationOutboxService service,
                                               WorkflowNotificationOutboxTransport transport,
                                               Clock clock) {
        this.service = Objects.requireNonNull(service); this.transport = Objects.requireNonNull(transport);
        this.clock = Objects.requireNonNull(clock);
    }
    /**
     * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
     */
    public WorkflowNotificationOutboxPublisher(WorkflowNotificationOutboxService service,
                                               WorkflowNotificationOutboxTransport transport) {
        this(service, transport, Clock.systemUTC());
    }

    /**
     * 执行一轮有界投递，逐条领取、发送和结算，返回不可变的本轮统计。
     */
    public PublishResult publishOnce() {
        // 扫描必须在外层事务和租户上下文之外开始，每条记录随后恢复自己的持久化租户身份。
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TenantContextHolder.currentOrNull() != null) {
            throw new IllegalStateException("Workflow Notification publisher must start outside context/transaction");
        }
        Instant now = instant();
        List<WorkflowNotificationOutboxModels.Candidate> candidates = service.findCandidates(
                now, now.minus(NotificationEventContract.OUTBOX_CLAIM_LEASE),
                NotificationEventContract.OUTBOX_MAX_CLAIMS);
        Mutable result = new Mutable(candidates.size());
        for (var candidate : candidates) {
            TenantContext context = TenantContext.ofPersistedTask(candidate.tenantId(),
                    ActorRef.service(NotificationEventContract.WORKFLOW.producerService()),
                    candidate.correlationId());
            try (TenantContextScope ignored = TenantContextScope.open(context)) {
                if (candidate.attemptCount() >= NotificationEventContract.OUTBOX_MAX_ATTEMPTS) {
                    recover(candidate, result);
                } else send(candidate, result);
            }
        }
        return result.freeze();
    }

    /**
     * 领取通知消息后校验冻结信封并发送，所有结算携带当前租约身份以隔离旧处理者。
     */
    private void send(WorkflowNotificationOutboxModels.Candidate candidate, Mutable result) {
        Instant claimedAt = instant(); String token = UUID.randomUUID().toString();
        Optional<WorkflowNotificationOutboxModels.Claimed> claim = service.claimForSend(candidate, token, claimedAt,
                        claimedAt.minus(NotificationEventContract.OUTBOX_CLAIM_LEASE));
        if (claim == null || claim.isEmpty()) { result.lostClaim++; return; }
        // 短事务完成带租约的认领后才进行网络发送，避免持有数据库事务等待 Broker。
        result.claimedForSend++;
        var row = claim.get(); WorkflowNotificationOutboxSendResult outcome;
        try {
            outcome = transport.send(WorkflowNotificationOutboxEnvelopeFactory.create(row));
            if (outcome == null) outcome = WorkflowNotificationOutboxSendResult.notConfirmed(RESULT_UNKNOWN, null);
        }
        catch (WorkflowNotificationOutboxEnvelopeFactory.InvalidContract invalid) {
            WorkflowNotificationOutboxSendResult failed = WorkflowNotificationOutboxSendResult.notConfirmed(INVALID_CONTRACT, null);
            Boolean recovered = service.rejectContract(row, instant(),
                    instant().minus(NotificationEventContract.OUTBOX_CLAIM_LEASE), failed.errorCode(),
                    "Persisted Notification contract failed validation",
                    WorkflowNotificationOutboxModels.recoveryId(row));
            if (Boolean.TRUE.equals(recovered)) result.recovered++; else result.lostCompletion++;
            return;
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); outcome = WorkflowNotificationOutboxSendResult.notConfirmed(RESULT_UNKNOWN, null);
        }
        catch (Exception unknown) { outcome = WorkflowNotificationOutboxSendResult.notConfirmed(RESULT_UNKNOWN, null); }
        Instant completed = instant(); Instant cutoff = completed.minus(NotificationEventContract.OUTBOX_CLAIM_LEASE);
        // 只有 Broker 明确确认才标记已发布；超时和未知结果仍可能已送达，重试依赖接收方幂等。
        if (outcome.status() == WorkflowNotificationOutboxSendResult.Status.SEND_OK) {
            Boolean done = service.markPublished(row, completed, cutoff);
            if (Boolean.TRUE.equals(done)) result.published++; else result.lostCompletion++;
        } else if (row.attemptCount() < NotificationEventContract.OUTBOX_MAX_ATTEMPTS) {
            Duration backoff = NotificationEventContract.FAILURE_BACKOFFS.get(row.attemptCount() - 1);
            WorkflowNotificationOutboxSendResult failed = outcome;
            Boolean done = service.markPending(row, completed, cutoff,
                    completed.plus(backoff), failed.errorCode());
            if (Boolean.TRUE.equals(done)) result.rescheduled++; else result.lostCompletion++;
        } else {
            WorkflowNotificationOutboxSendResult failed = outcome;
            Boolean done = service.moveToRecovery(row, completed, cutoff,
                    failed.errorCode(), sanitize(failed.safeErrorSummary()),
                    WorkflowNotificationOutboxModels.recoveryId(row));
            if (Boolean.TRUE.equals(done)) result.recovered++; else result.lostCompletion++;
        }
    }

    /**
     * 领取耗尽发送次数的通知消息处理权，再按当前租约提交恢复状态。
     */
    private void recover(WorkflowNotificationOutboxModels.Candidate candidate, Mutable result) {
        Instant claimedAt = instant(); String token = UUID.randomUUID().toString();
        Optional<WorkflowNotificationOutboxModels.Claimed> claim = service.claimForRecovery(candidate, token, claimedAt,
                        claimedAt.minus(NotificationEventContract.OUTBOX_CLAIM_LEASE));
        if (claim == null || claim.isEmpty()) { result.lostClaim++; return; }
        result.claimedForRecovery++;
        Instant completed = instant();
        Boolean done = service.moveToRecovery(claim.get(), completed,
                completed.minus(NotificationEventContract.OUTBOX_CLAIM_LEASE), RESULT_UNKNOWN, null,
                WorkflowNotificationOutboxModels.recoveryId(claim.get()));
        if (Boolean.TRUE.equals(done)) result.recovered++; else result.lostCompletion++;
    }

    /**
     * 读取发布器注入时钟并截断为微秒，用于租约和消息结算时间比较。
     */
    private Instant instant() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    /**
     * 清理并缩减错误摘要，避免把无界或超长异常文本写入恢复记录。
     */
    private static String sanitize(String value) {
        if (value == null) return null; String clean = value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return clean.isEmpty() ? null : clean.substring(0, Math.min(1000, clean.length()));
    }

    /**
     * 工作流处理结果封装，组合业务快照及分页或幂等元信息供调用入口使用。
     */
    public record PublishResult(int scanned, int claimedForSend, int claimedForRecovery,
                                int published, int rescheduled, int recovered,
                                int lostClaim, int lostCompletion) { }
    /**
     * 单轮发布的内部可变计数器，结束后冻结为不可变结果供调用方读取。
     */
    private static final class Mutable {
        private final int scanned; private int claimedForSend, claimedForRecovery, published,
                rescheduled, recovered, lostClaim, lostCompletion;
        /**
         * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
         */
        Mutable(int scanned) { this.scanned = scanned; }
        /**
         * 将本轮可变计数转为不可变结果，避免后续变更影响调用方统计。
         */
        PublishResult freeze() { return new PublishResult(scanned, claimedForSend, claimedForRecovery,
                published, rescheduled, recovered, lostClaim, lostCompletion); }
    }
}
