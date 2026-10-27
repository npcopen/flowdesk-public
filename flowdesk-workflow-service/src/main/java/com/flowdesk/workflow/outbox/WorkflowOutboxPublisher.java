package com.flowdesk.workflow.outbox;

import com.flowdesk.workflow.model.WorkflowOutboxModels;
import com.flowdesk.workflow.service.WorkflowOutboxService;

import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.workflow.event.WorkflowReverseEventContract;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 两个独立反向事件契约共用的有限重试发布器，发送确认后再结算 Outbox 状态。
 */
public final class WorkflowOutboxPublisher {
    static final String RESULT_UNKNOWN = "BROKER_RESULT_UNKNOWN";
    static final String INVALID_CONTRACT = "OUTBOX_CONTRACT_INVALID";

    private final WorkflowOutboxService service;
    private final WorkflowOutboxTransport transport;
    private final Clock clock;

    /**
     * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
     */
    public WorkflowOutboxPublisher(WorkflowOutboxService service,
                                   WorkflowOutboxTransport transport) {
        this(service, transport, Clock.systemUTC());
    }

    /**
     * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
     */
    WorkflowOutboxPublisher(WorkflowOutboxService service,
                            WorkflowOutboxTransport transport,
                            Clock clock) {
        this.service = service;
        this.transport = transport;
        this.clock = clock;
    }

    /**
     * 执行一轮有界投递，逐条领取、发送和结算，返回不可变的本轮统计。
     */
    public PublishResult publishOnce() {
        // 扫描必须在外层事务和租户上下文之外开始，每条记录随后恢复自己的持久化租户身份。
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TenantContextHolder.currentOrNull() != null) {
            throw new IllegalStateException("Workflow Outbox poll must start outside context/tx");
        }
        Instant now = clock.instant();
        List<WorkflowOutboxModels.Candidate> candidates = service.findCandidates(
                now, now.minus(WorkflowReverseEventContract.OUTBOX_CLAIM_LEASE),
                WorkflowReverseEventContract.OUTBOX_MAX_CLAIMS);
        Mutable result = new Mutable(candidates.size());
        for (WorkflowOutboxModels.Candidate candidate : candidates) {
            TenantContext context = TenantContext.ofPersistedTask(candidate.tenantId(),
                    ActorRef.service(WorkflowReverseEventContract.PRODUCER_SERVICE),
                    candidate.correlationId());
            try (TenantContextScope ignored = TenantContextScope.open(context)) {
                if (candidate.attemptCount()
                        == WorkflowReverseEventContract.OUTBOX_MAX_ATTEMPTS) {
                    recoverExpired(candidate, result);
                }
                else {
                    send(candidate, result);
                }
            }
        }
        return result.freeze();
    }

    /**
     * 领取并校验原反向消息，事务外发送后按明确代理结果及本次令牌结算。
     */
    private void send(WorkflowOutboxModels.Candidate candidate, Mutable result) {
        Instant claimedAt = clock.instant();
        String token = UUID.randomUUID().toString();
        Optional<WorkflowOutboxModels.Claimed> claimed = service.claimForSend(candidate, token, claimedAt,
                        claimedAt.minus(WorkflowReverseEventContract.OUTBOX_CLAIM_LEASE));
        if (claimed == null || claimed.isEmpty()) {
            result.lostClaim++;
            return;
        }
        // 短事务完成带租约的认领后才进行网络发送，避免持有数据库事务等待 Broker。
        result.claimedForSend++;
        WorkflowOutboxModels.Claimed row = claimed.get();
        WorkflowOutboxSendResult outcome;
        try {
            outcome = transport.send(WorkflowOutboxEnvelopeFactory.create(row));
            if (outcome == null) {
                outcome = WorkflowOutboxSendResult.notConfirmed(RESULT_UNKNOWN, null);
            }
        }
        catch (WorkflowOutboxEnvelopeFactory.InvalidContract invalid) {
            outcome = WorkflowOutboxSendResult.notConfirmed(INVALID_CONTRACT, null);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            outcome = WorkflowOutboxSendResult.notConfirmed(RESULT_UNKNOWN, null);
        }
        catch (Exception unknown) {
            outcome = WorkflowOutboxSendResult.notConfirmed(RESULT_UNKNOWN, null);
        }

        Instant completed = clock.instant();
        Instant cutoff = completed.minus(WorkflowReverseEventContract.OUTBOX_CLAIM_LEASE);
        // 只有 Broker 明确确认才标记已发布；超时和未知结果仍可能已送达，重试依赖接收方幂等。
        if (outcome.status() == WorkflowOutboxSendResult.Status.SEND_OK) {
            Boolean done = service.markPublished(row, completed, cutoff);
            if (Boolean.TRUE.equals(done)) result.published++; else result.lostCompletion++;
            return;
        }
        if (row.attemptCount() < WorkflowReverseEventContract.OUTBOX_MAX_ATTEMPTS) {
            Instant next = completed.plus(WorkflowReverseEventContract.FAILURE_BACKOFFS
                    .get(row.attemptCount() - 1));
            WorkflowOutboxSendResult failed = outcome;
            Boolean done = service.markPending(
                    row, completed, cutoff, next, failed.errorCode());
            if (Boolean.TRUE.equals(done)) result.rescheduled++; else result.lostCompletion++;
        }
        else {
            WorkflowOutboxSendResult failed = outcome;
            Boolean done = service.moveToRecovery(
                    row, completed, cutoff, failed.errorCode(),
                    sanitize(failed.safeSummary()), UUID.randomUUID());
            if (Boolean.TRUE.equals(done)) result.recovered++; else result.lostCompletion++;
        }
    }

    /**
     * 领取耗尽发送次数消息的恢复租约，再结算为人工可处理的恢复事实。
     */
    private void recoverExpired(WorkflowOutboxModels.Candidate candidate, Mutable result) {
        Instant now = clock.instant();
        String token = UUID.randomUUID().toString();
        Optional<WorkflowOutboxModels.Claimed> claimed = service.claimForRecovery(candidate, token, now,
                        now.minus(WorkflowReverseEventContract.OUTBOX_CLAIM_LEASE));
        if (claimed == null || claimed.isEmpty()) {
            result.lostClaim++;
            return;
        }
        result.claimedForRecovery++;
        Instant completed = clock.instant();
        Boolean done = service.moveToRecovery(
                claimed.get(), completed,
                completed.minus(WorkflowReverseEventContract.OUTBOX_CLAIM_LEASE),
                RESULT_UNKNOWN, null, UUID.randomUUID());
        if (Boolean.TRUE.equals(done)) result.recovered++; else result.lostCompletion++;
    }

    /**
     * 清理并缩减错误摘要，避免把无界或超长异常文本写入恢复记录。
     */
    private static String sanitize(String value) {
        if (value == null) return null;
        String cleaned = value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        if (cleaned.isEmpty()) return null;
        return cleaned.substring(0, Math.min(1000, cleaned.length()));
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
        private final int scanned;
        private int claimedForSend, claimedForRecovery, published, rescheduled, recovered,
                lostClaim, lostCompletion;
        /**
         * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
         */
        private Mutable(int scanned) { this.scanned = scanned; }
        /**
         * 将本轮可变计数转为不可变结果，避免后续变更影响调用方统计。
         */
        private PublishResult freeze() {
            return new PublishResult(scanned, claimedForSend, claimedForRecovery, published,
                    rescheduled, recovered, lostClaim, lostCompletion);
        }
    }
}
