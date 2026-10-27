package com.flowdesk.ticket.outbox.impl;

import com.flowdesk.ticket.outbox.*;

import com.flowdesk.ticket.model.outbox.*;
import com.flowdesk.ticket.persistence.Uuids;

import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.ticket.event.TicketEventContract;
import org.springframework.dao.DuplicateKeyException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 固定流程启动契约的 Outbox 持久化边界，通过状态、版本及租约令牌约束消息领取和结算。 */
@Service
public final class WorkflowStartDispatchServiceImpl implements WorkflowStartDispatchService {

    private static final String CONTRACT_REF =
            TicketEventContract.WorkflowStartRequested.CONTRACT_REF;
    private static final int CONTRACT_VERSION =
            TicketEventContract.WorkflowStartRequested.EVENT_VERSION;
    private static final String AGGREGATE_TYPE =
            TicketEventContract.WorkflowStartRequested.AGGREGATE_TYPE;
    private static final int MAX_AUTOMATIC_SENDS =
            TicketEventContract.WorkflowStartRequested.MAX_AUTOMATIC_SENDS;

    private final TicketOutboxPersistenceMapper mapper;

    /**
     * 构造工作流启动发布组件并保存调用依赖。
     */
    public WorkflowStartDispatchServiceImpl(TicketOutboxPersistenceMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 跨租户按批次发现任务，仅返回已持久化的任务引用。
     * 后续完整读取及变更均先限定租户，并在恢复出的任务上下文内执行。
     */
    public List<Candidate> findCandidates(Instant now, Instant leaseCutoff, int limit) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseCutoff, "leaseCutoff");
        if (limit < 1 || limit > TicketEventContract.WorkflowStartRequested.MAX_CLAIMS_PER_RUN) {
            throw new IllegalArgumentException("limit must be between 1 and 50");
        }
        return mapper.findCandidatesSelect(CONTRACT_REF, CONTRACT_VERSION, AGGREGATE_TYPE, MAX_AUTOMATIC_SENDS, Timestamp.from(now), Timestamp.from(leaseCutoff), limit);
    }

    /** 发送前即时领取一条到期消息，并消耗一次自动发送预算。 */
    public Optional<ClaimedOutbox> claimForSend(
            Candidate candidate, String claimToken, Instant claimedAt, Instant leaseCutoff) {
        requireTransaction("claimForSend");
        requireClaimToken(claimToken);
        Objects.requireNonNull(candidate, "candidate");
        requireTenant(candidate.tenantId());
        int updated;
        if ("PENDING".equals(candidate.status())) {
            updated = mapper.claimForSendUpdate(claimToken, timestamp(claimedAt), timestamp(claimedAt), Uuids.toBytes(candidate.tenantId()), Uuids.toBytes(candidate.outboxId()), candidate.attemptCount(), MAX_AUTOMATIC_SENDS, candidate.lockVersion(), timestamp(claimedAt));
        }
        else if ("PUBLISHING".equals(candidate.status()) && candidate.lockedBy() != null) {
            updated = mapper.claimForSendUpdate2(claimToken, timestamp(claimedAt), timestamp(claimedAt), Uuids.toBytes(candidate.tenantId()), Uuids.toBytes(candidate.outboxId()), candidate.attemptCount(), MAX_AUTOMATIC_SENDS, candidate.lockedBy(), candidate.lockVersion(), timestamp(leaseCutoff));
        }
        else {
            return Optional.empty();
        }
        if (updated != 1) {
            return Optional.empty();
        }
        return Optional.of(findClaimed(candidate.tenantId(), candidate.outboxId(), claimToken,
                candidate.attemptCount() + 1, candidate.lockVersion() + 1)
                .orElseThrow(() -> new IllegalStateException(
                        "Claimed Outbox row could not be reread in its claim transaction")));
    }

    /** 接管耗尽的消息仅用于收敛恢复状态；既不增加已消耗的第八次尝试，也不允许第九次发送。 */
    public Optional<ClaimedOutbox> claimForRecovery(
            Candidate candidate, String claimToken, Instant claimedAt, Instant leaseCutoff) {
        requireTransaction("claimForRecovery");
        requireClaimToken(claimToken);
        Objects.requireNonNull(candidate, "candidate");
        requireTenant(candidate.tenantId());
        if (candidate.attemptCount() != MAX_AUTOMATIC_SENDS) {
            return Optional.empty();
        }
        int updated;
        if ("PENDING".equals(candidate.status())) {
            updated = mapper.claimForRecoveryUpdate(claimToken, timestamp(claimedAt), timestamp(claimedAt), Uuids.toBytes(candidate.tenantId()), Uuids.toBytes(candidate.outboxId()), MAX_AUTOMATIC_SENDS, candidate.lockVersion(), timestamp(claimedAt));
        }
        else if ("PUBLISHING".equals(candidate.status()) && candidate.lockedBy() != null) {
            updated = mapper.claimForRecoveryUpdate2(claimToken, timestamp(claimedAt), timestamp(claimedAt), Uuids.toBytes(candidate.tenantId()), Uuids.toBytes(candidate.outboxId()), MAX_AUTOMATIC_SENDS, candidate.lockedBy(), candidate.lockVersion(), timestamp(leaseCutoff));
        }
        else {
            return Optional.empty();
        }
        if (updated != 1) {
            return Optional.empty();
        }
        return Optional.of(findClaimed(candidate.tenantId(), candidate.outboxId(), claimToken,
                MAX_AUTOMATIC_SENDS, candidate.lockVersion() + 1)
                .orElseThrow(() -> new IllegalStateException(
                        "Recovery-only claim could not be reread in its claim transaction")));
    }

    /**
     * 在领取身份仍有效时标记发布成功，阻止过期工作器覆盖新结果。
     */
    public boolean markPublished(ClaimedOutbox claimed, Instant confirmedAt, Instant leaseCutoff) {
        requireTransaction("markPublished");
        requireTenant(claimed.tenantId());
        int updated = mapper.markPublishedUpdate(timestamp(confirmedAt), timestamp(confirmedAt), Uuids.toBytes(claimed.tenantId()), Uuids.toBytes(claimed.outboxId()), claimed.attemptCount(), claimed.claimToken(), claimed.lockVersion(), timestamp(leaseCutoff));
        if (updated == 1) {
            mapper.markPublishedUpdate2(timestamp(confirmedAt), timestamp(confirmedAt), Uuids.toBytes(claimed.tenantId()), Uuids.toBytes(claimed.outboxId()));
        }
        return updated == 1;
    }

    /**
     * 将未确认成功的消息重新排队，记录退避后的下次尝试时间。
     */
    public boolean markPending(ClaimedOutbox claimed, Instant completedAt, Instant leaseCutoff,
                               Instant nextAttemptAt, String errorCode) {
        requireTransaction("markPending");
        requireTenant(claimed.tenantId());
        if (claimed.attemptCount() < 1 || claimed.attemptCount() >= MAX_AUTOMATIC_SENDS) {
            throw new IllegalArgumentException("Only attempts 1 through 7 may return to PENDING");
        }
        return mapper.markPendingUpdate(timestamp(nextAttemptAt), errorCode, timestamp(completedAt), Uuids.toBytes(claimed.tenantId()), Uuids.toBytes(claimed.outboxId()), claimed.attemptCount(), claimed.claimToken(), claimed.lockVersion(), timestamp(leaseCutoff)) == 1;
    }

    /** 在工单侧三个关联表中原子收敛第八次尝试后的恢复边界。 */
    public boolean moveToRecovery(ClaimedOutbox claimed, Instant completedAt, Instant leaseCutoff,
                                  String errorCode, String safeErrorSummary, UUID recoveryId) {
        requireTransaction("moveToRecovery");
        requireTenant(claimed.tenantId());
        if (claimed.attemptCount() != MAX_AUTOMATIC_SENDS) {
            throw new IllegalArgumentException("Recovery requires the eighth consumed attempt");
        }
        int outboxUpdated = mapper.moveToRecoveryUpdate(timestamp(completedAt), errorCode, timestamp(completedAt), Uuids.toBytes(claimed.tenantId()), Uuids.toBytes(claimed.outboxId()), MAX_AUTOMATIC_SENDS, claimed.claimToken(), claimed.lockVersion(), timestamp(leaseCutoff));
        if (outboxUpdated == 0) {
            return false;
        }
        if (outboxUpdated != 1) {
            throw new IllegalStateException("Outbox recovery CAS updated an unexpected row count");
        }

        LinkState link = mapper.moveToRecoverySelect2(Uuids.toBytes(claimed.tenantId()), Uuids.toBytes(claimed.aggregateId())).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "WorkflowStartLink is missing for exhausted Outbox"));
        if (!"REQUESTED".equals(link.status())) {
            throw new IllegalStateException("WorkflowStartLink is not REQUESTED at Outbox exhaustion");
        }
        int linkUpdated = mapper.moveToRecoveryUpdate3(timestamp(completedAt), errorCode, MAX_AUTOMATIC_SENDS, timestamp(completedAt), timestamp(completedAt), Uuids.toBytes(claimed.tenantId()), Uuids.toBytes(claimed.aggregateId()), link.lockVersion());
        if (linkUpdated != 1) {
            throw new IllegalStateException("WorkflowStartLink recovery CAS lost its version");
        }

        ensureRecovery(claimed, completedAt, errorCode, safeErrorSummary, recoveryId);
        return true;
    }

    /**
     * 读取本次领取身份对应快照，避免使用过期任务的旧状态。
     */
    private Optional<ClaimedOutbox> findClaimed(UUID tenantId, UUID outboxId, String claimToken,
                                                int attemptCount, long lockVersion) {
        return mapper.findClaimedSelect(Uuids.toBytes(tenantId), Uuids.toBytes(outboxId), attemptCount, claimToken, lockVersion).stream().findFirst();
    }

    /**
     * 查询或幂等建立恢复记录，已有记录必须与来源身份一致。
     */
    private void ensureRecovery(ClaimedOutbox claimed, Instant completedAt, String errorCode,
                                String safeErrorSummary, UUID recoveryId) {
        Optional<RecoveryState> existing = findRecovery(claimed.tenantId(), claimed.outboxId());
        if (existing.isPresent()) {
            validateRecovery(existing.get(), claimed);
            return;
        }
        try {
            mapper.ensureRecoveryUpdate(Uuids.toBytes(claimed.tenantId()), Uuids.toBytes(recoveryId), Uuids.toBytes(claimed.outboxId()), claimed.businessIntentKey(), MAX_AUTOMATIC_SENDS, errorCode, safeErrorSummary, timestamp(completedAt), timestamp(completedAt), timestamp(completedAt));
        }
        catch (DuplicateKeyException duplicate) {
            RecoveryState concurrent = findRecovery(claimed.tenantId(), claimed.outboxId())
                    .orElseThrow(() -> duplicate);
            validateRecovery(concurrent, claimed);
        }
    }

    /**
     * 读取来源对应恢复记录，供去重、状态推进和审计检查。
     */
    private Optional<RecoveryState> findRecovery(UUID tenantId, UUID outboxId) {
        return mapper.findRecoverySelect(Uuids.toBytes(tenantId), Uuids.toBytes(outboxId)).stream().findFirst();
    }

    /**
     * 核对恢复方向、来源及状态，拒绝不支持或不满足条件的重放。
     */
    private static void validateRecovery(RecoveryState recovery, ClaimedOutbox claimed) {
        if (!Arrays.equals(recovery.businessIntentKey(), claimed.businessIntentKey())
                || !"OPEN".equals(recovery.state())
                || recovery.attemptCount() != MAX_AUTOMATIC_SENDS
                || recovery.deadLetteredAt() != null) {
            throw new IllegalStateException("Existing Outbox recovery fact conflicts with exhaustion");
        }
    }

    /**
     * 校验发件箱领取令牌，后续结算依靠该身份约束过期执行者。
     */
    private static void requireClaimToken(String claimToken) {
        if (claimToken == null || claimToken.isBlank() || claimToken.length() > 96) {
            throw new IllegalArgumentException("claimToken must be 1..96 characters");
        }
    }

    /**
     * 要求调用处于数据库事务中，保证关联事实可以原子提交或回滚。
     */
    private static void requireTransaction(String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(operation + " requires an active local transaction");
        }
    }

    /**
     * 校验传入租户与当前上下文一致，阻止跨租户持久化操作。
     */
    private static void requireTenant(UUID tenantId) {
        TenantContextHolder.current().requireTenant(tenantId);
    }

    /**
     * 解析时间文本或转换数据库时间值，遵循空值约定并保留业务精度。
     */
    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(Objects.requireNonNull(value, "timestamp value"));
    }








}
