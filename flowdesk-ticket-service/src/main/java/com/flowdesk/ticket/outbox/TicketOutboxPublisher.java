package com.flowdesk.ticket.outbox;

import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.ticket.event.TicketEventContract;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 单次有界执行工单流程启动 Outbox 的可靠发布状态机。
 * 领取和完成各自使用短本地事务，同步 Broker 调用位于两个事务之外，避免网络等待占用数据库锁。
 */
public final class TicketOutboxPublisher {

    static final String UNKNOWN_RESULT = "BROKER_RESULT_UNKNOWN";
    static final String INVALID_CONTRACT = "OUTBOX_CONTRACT_INVALID";

    private final WorkflowStartDispatchService repository;
    private final TicketOutboxTransport transport;
    private final Clock clock;
    private final TransactionTemplate transactions;

    /**
     * 构造工作流启动发布组件并保存调用依赖；支持注入时钟以控制时间边界。
     */
    public TicketOutboxPublisher(WorkflowStartDispatchService repository,
                                 TicketOutboxTransport transport,
                                 PlatformTransactionManager transactionManager,
                                 Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 构造工作流启动发布组件并保存调用依赖。
     */
    public TicketOutboxPublisher(WorkflowStartDispatchService repository,
                                 TicketOutboxTransport transport,
                                 PlatformTransactionManager transactionManager) {
        this(repository, transport, transactionManager, Clock.systemUTC());
    }

    /** 单次最多扫描 50 个候选，逐条即时领取并发送。 */
    public PublishRunResult publishOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox publisher must not run inside a caller transaction");
        }
        if (TenantContextHolder.currentOrNull() != null) {
            throw new IllegalStateException("Outbox publisher must start without a thread TenantContext");
        }

        Instant scanTime = clock.instant();
        List<com.flowdesk.ticket.model.outbox.Candidate> candidates = repository.findCandidates(
                scanTime,
                scanTime.minus(TicketEventContract.WorkflowStartRequested.CLAIM_LEASE),
                TicketEventContract.WorkflowStartRequested.MAX_CLAIMS_PER_RUN);

        MutableRunResult result = new MutableRunResult(candidates.size());
        for (com.flowdesk.ticket.model.outbox.Candidate candidate : candidates) {
            TenantContext context = TenantContext.ofPersistedTask(
                    candidate.tenantId(),
                    ActorRef.service(TicketEventContract.WorkflowStartRequested.PRODUCER_SERVICE),
                    candidate.correlationId());
            try (TenantContextScope ignored = TenantContextScope.open(context)) {
                if (candidate.attemptCount()
                        == TicketEventContract.WorkflowStartRequested.MAX_AUTOMATIC_SENDS) {
                    recoverExpiredExhausted(candidate, result);
                }
                else {
                    claimSendAndComplete(candidate, result);
                }
            }
        }
        return result.freeze();
    }

    /**
     * 领取先提交，再调用 Broker；结算必须仍持有相同令牌、版本及有效租约。
     * 未知发送结果采用至少一次重试，由下游业务幂等吸收可能的重复。
     */
    private void claimSendAndComplete(com.flowdesk.ticket.model.outbox.Candidate candidate,
                                      MutableRunResult result) {
        Instant claimedAt = clock.instant();
        String token = UUID.randomUUID().toString();
        Optional<com.flowdesk.ticket.model.outbox.ClaimedOutbox> claimed = transactions.execute(status ->
                repository.claimForSend(candidate, token, claimedAt,
                        claimedAt.minus(TicketEventContract.WorkflowStartRequested.CLAIM_LEASE)));
        if (claimed == null || claimed.isEmpty()) {
            result.lostClaim++;
            return;
        }
        result.claimedForSend++;

        com.flowdesk.ticket.model.outbox.ClaimedOutbox row = claimed.get();
        TicketOutboxSendResult sendResult;
        try {
            TicketOutboxMessage message = WorkflowStartV1EnvelopeFactory.create(row);
            sendResult = transport.send(message);
            if (sendResult == null) {
                sendResult = TicketOutboxSendResult.notConfirmed(UNKNOWN_RESULT, null);
            }
        }
        catch (WorkflowStartV1EnvelopeFactory.InvalidOutboxContractException invalid) {
            sendResult = TicketOutboxSendResult.notConfirmed(INVALID_CONTRACT, null);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            sendResult = TicketOutboxSendResult.notConfirmed(UNKNOWN_RESULT, null);
        }
        catch (Exception unknown) {
            // 异常文本可能包含凭据或服务地址，此处禁止持久化。
            sendResult = TicketOutboxSendResult.notConfirmed(UNKNOWN_RESULT, null);
        }

        TicketOutboxSendResult outcome = sendResult;
        Instant completedAt = clock.instant();
        Instant leaseCutoff = completedAt.minus(
                TicketEventContract.WorkflowStartRequested.CLAIM_LEASE);
        if (outcome.status() == TicketOutboxSendResult.Status.SEND_OK) {
            Boolean published = transactions.execute(status ->
                    repository.markPublished(row, completedAt, leaseCutoff));
            if (Boolean.TRUE.equals(published)) {
                result.published++;
            }
            else {
                result.lostCompletion++;
            }
            return;
        }

        String safeSummary = sanitizeSummary(outcome.safeErrorSummary());
        if (row.attemptCount()
                < TicketEventContract.WorkflowStartRequested.MAX_AUTOMATIC_SENDS) {
            Duration backoff = TicketEventContract.WorkflowStartRequested.FAILURE_BACKOFFS
                    .get(row.attemptCount() - 1);
            Boolean pending = transactions.execute(status -> repository.markPending(
                    row, completedAt, leaseCutoff, completedAt.plus(backoff),
                    outcome.errorCode()));
            if (Boolean.TRUE.equals(pending)) {
                result.rescheduled++;
            }
            else {
                result.lostCompletion++;
            }
        }
        else {
            Boolean recovered = transactions.execute(status -> repository.moveToRecovery(
                    row, completedAt, leaseCutoff, outcome.errorCode(), safeSummary,
                    UUID.randomUUID()));
            if (Boolean.TRUE.equals(recovered)) {
                result.recovered++;
            }
            else {
                result.lostCompletion++;
            }
        }
    }

    /** 接管已经耗尽发送次数的过期租约，只写恢复事实，不再额外发送。 */
    private void recoverExpiredExhausted(com.flowdesk.ticket.model.outbox.Candidate candidate,
                                         MutableRunResult result) {
        Instant claimedAt = clock.instant();
        String token = UUID.randomUUID().toString();
        Optional<com.flowdesk.ticket.model.outbox.ClaimedOutbox> claimed = transactions.execute(status ->
                repository.claimForRecovery(candidate, token, claimedAt,
                        claimedAt.minus(TicketEventContract.WorkflowStartRequested.CLAIM_LEASE)));
        if (claimed == null || claimed.isEmpty()) {
            result.lostClaim++;
            return;
        }
        result.claimedForRecovery++;
        Instant completedAt = clock.instant();
        Boolean recovered = transactions.execute(status -> repository.moveToRecovery(
                claimed.get(), completedAt,
                completedAt.minus(TicketEventContract.WorkflowStartRequested.CLAIM_LEASE),
                UNKNOWN_RESULT, null, UUID.randomUUID()));
        if (Boolean.TRUE.equals(recovered)) {
            result.recovered++;
        }
        else {
            result.lostCompletion++;
        }
    }

    /**
     * 清理并截断安全错误摘要，避免控制字符或过长详情污染审计。
     */
    private static String sanitizeSummary(String summary) {
        if (summary == null) {
            return null;
        }
        StringBuilder sanitized = new StringBuilder(Math.min(summary.length(), 1000));
        boolean previousSpace = false;
        for (int i = 0; i < summary.length() && sanitized.length() < 1000; i++) {
            char value = summary.charAt(i);
            if (Character.isISOControl(value) || Character.isWhitespace(value)) {
                if (!previousSpace && sanitized.length() > 0) {
                    sanitized.append(' ');
                    previousSpace = true;
                }
            }
            else {
                sanitized.append(value);
                previousSpace = false;
            }
        }
        String value = sanitized.toString().trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * 一次工作流启动发布批次的统计快照，反映处理与领取竞争情况。
     */
    public record PublishRunResult(int scanned, int claimedForSend, int claimedForRecovery,
                                   int published, int rescheduled, int recovered,
                                   int lostClaim, int lostCompletion) {
    }

    /**
     * 发布批次计数累加器，避免处理中频繁创建结果对象。
     */
    private static final class MutableRunResult {
        private final int scanned;
        private int claimedForSend;
        private int claimedForRecovery;
        private int published;
        private int rescheduled;
        private int recovered;
        private int lostClaim;
        private int lostCompletion;

        /**
         * 初始化单轮统计累加器并保存扫描数量，其余计数从零开始。
         */
        private MutableRunResult(int scanned) {
            this.scanned = scanned;
        }

        /**
         * 把本轮可变计数转为不可变统计，供调用方观察处理进度。
         */
        private PublishRunResult freeze() {
            return new PublishRunResult(scanned, claimedForSend, claimedForRecovery,
                    published, rescheduled, recovered, lostClaim, lostCompletion);
        }
    }
}
