package com.flowdesk.workflow.service.recovery.impl;

import com.flowdesk.workflow.start.*;
import com.flowdesk.workflow.model.recovery.WorkflowRecoveryModels.*;
import com.flowdesk.workflow.service.recovery.*;
import com.flowdesk.workflow.dto.*;
import com.flowdesk.common.recovery.RecoveryReplayResumer;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.flowdesk.workflow.dto.WorkflowRecoverySupport;
import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.web.ApiException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 人工重放受理事务（API-060）：记录尝试与执行租约，Inbox 交给后台任务，Outbox 重新入发送队列。
 */
public final class WorkflowRecoveryAcceptanceServiceImpl implements WorkflowRecoveryAcceptanceService {

    private final WorkflowRecoveryMapper mapper;
    private final TransactionTemplate transactions;

    /**
     * 注入恢复映射及事务管理器，将人工请求领取、重放审计与原 Outbox 排队保存在同一接纳事务。
     */
    public WorkflowRecoveryAcceptanceServiceImpl(WorkflowRecoveryMapper mapper,
                                      PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 事务内接纳人工重放，核验幂等锚点并按 Inbox 或 Outbox 来源选择恢复路径。
     */
    public Acceptance accept(TenantContext context,
                      UUID recoveryId,
                      UUID actorId,
                      String reason,
                      String idempotencyKey,
                      long expectedVersion) {
        if (!(context.actor() instanceof ActorRef.Human human)
                || !human.userId().equals(actorId)) {
            throw new IllegalStateException("Workflow replay actor is not the current human");
        }
        UUID auditId = WorkflowRecoveryIdempotency.replayAuditId(
                context, recoveryId, idempotencyKey);
        String requestDigest = WorkflowRecoveryIdempotency.requestDigest(recoveryId, reason);
        Acceptance acceptance = transactions.execute(status -> acceptInside(
                context, recoveryId, actorId, reason, expectedVersion,
                auditId, requestDigest));
        if (acceptance == null) {
            throw new IllegalStateException("Workflow recovery transaction returned no result");
        }
        return acceptance;
    }

    // 先核对已受理的请求锚点；同键重试不应被后续变化的版本号和状态误拒绝。
    /**
     * 锁定恢复事实并核验版本与原来源，登记操作者和原因后返回已提交工作项。
     */
    private Acceptance acceptInside(TenantContext context,
                                    UUID recoveryId,
                                    UUID actorId,
                                    String reason,
                                    long expectedVersion,
                                    UUID auditId,
                                    String requestDigest) {
        RecoveryFact candidate = findRecovery(context.tenantId(), recoveryId, false)
                .orElseThrow(WorkflowRecoveryAcceptanceServiceImpl::routeMismatch);
        if ("OUTBOX".equals(candidate.direction())) {
            return acceptOutboxInside(context, recoveryId, actorId, reason, expectedVersion,
                    auditId, requestDigest, candidate);
        }
        if (!"INBOX".equals(candidate.direction())) {
            throw routeMismatch();
        }
        ReplaySource source = findSource(context.tenantId(), candidate.sourceRecordId(), true)
                .orElseThrow(WorkflowRecoveryAcceptanceServiceImpl::routeMismatch);
        RecoveryFact recovery = findRecovery(context.tenantId(), recoveryId, true)
                .filter(fact -> "INBOX".equals(fact.direction())
                        && fact.sourceRecordId().equals(source.inboxId()))
                .orElseThrow(WorkflowRecoveryAcceptanceServiceImpl::routeMismatch);

        var prior = findAudit(context.tenantId(), auditId);
        if (prior.isPresent()) {
            validateIdempotencyAnchor(prior.get(), recoveryId, actorId,
                    requestDigest, source.correlationId());
            return new Acceptance(
                    WorkflowRecoveryViews.toView(recovery), true, null);
        }

        if (!"OPEN".equals(recovery.state())) {
            if ("REPLAYING".equals(recovery.state())) {
                throw ApiException.conflict(
                        "RECOVERY_REPLAY_CONFLICT", "该恢复记录已有人工重放正在处理。");
            }
            throw ApiException.conflict(
                    "RECOVERY_NOT_REPLAYABLE", "该恢复记录不再允许人工重放。");
        }
        if (!"RECOVERY_REQUIRED".equals(source.status())) {
            throw ApiException.conflict(
                    "RECOVERY_NOT_REPLAYABLE", "原 Inbox 已经终态收敛，不能再次重放。");
        }
        if (recovery.lockVersion() != expectedVersion) {
            throw WorkflowRecoverySupport.versionConflict(recovery.lockVersion());
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID attemptCorrelationId = UUID.randomUUID();
        // 每次受理生成独立尝试身份；它与持久化租约共同约束后续执行和结算。
        int changed = markReplaying(context.tenantId(), recovery, actorId,
                reason, attemptCorrelationId, now);
        if (changed != 1) {
            throw ApiException.conflict(
                    "RECOVERY_REPLAY_CONFLICT", "该恢复记录的重放裁决已被其他请求取得。");
        }
        insertReplayRequestAudit(context.tenantId(), auditId, recoveryId,
                actorId, requestDigest, attemptCorrelationId, source.correlationId(), now);
        RecoveryFact accepted = findRecovery(context.tenantId(), recoveryId, false)
                .orElseThrow(WorkflowRecoveryAcceptanceServiceImpl::routeMismatch);
        ReplayWork work = new ReplayWork(
                context.tenantId(), recoveryId, recovery.sourceRecordId(), actorId,
                reason, attemptCorrelationId, source.correlationId(), accepted.nextAttemptAt());
        return new Acceptance(WorkflowRecoveryViews.toView(accepted), false, work);
    }

    /**
     * 核验原 Outbox，原子重新排队并记录重放审计，保持消息身份与载荷不变。
     */
    private Acceptance acceptOutboxInside(TenantContext context,
                                           UUID recoveryId,
                                           UUID actorId,
                                           String reason,
                                           long expectedVersion,
                                           UUID auditId,
                                           String requestDigest,
                                           RecoveryFact candidate) {
        OutboxReplaySource source = findOutboxSource(context.tenantId(), candidate.sourceRecordId(), true)
                .filter(WorkflowRecoveryAcceptanceServiceImpl::activatedOutboxSource)
                .orElseThrow(WorkflowRecoveryAcceptanceServiceImpl::routeMismatch);
        RecoveryFact recovery = findRecovery(context.tenantId(), recoveryId, true)
                .filter(fact -> "OUTBOX".equals(fact.direction())
                        && fact.sourceRecordId().equals(source.outboxId()))
                .orElseThrow(WorkflowRecoveryAcceptanceServiceImpl::routeMismatch);
        var prior = findAudit(context.tenantId(), auditId);
        if (prior.isPresent()) {
            validateIdempotencyAnchor(prior.get(), recoveryId, actorId,
                    requestDigest, source.correlationId());
            return new Acceptance(WorkflowRecoveryViews.toView(recovery), true, null);
        }
        if (!"OPEN".equals(recovery.state())) {
            if ("REPLAYING".equals(recovery.state())) {
                throw ApiException.conflict("RECOVERY_REPLAY_CONFLICT",
                        "该恢复记录已有人工重放正在处理。");
            }
            throw ApiException.conflict("RECOVERY_NOT_REPLAYABLE",
                    "该恢复记录不再允许人工重放。");
        }
        if (!"RECOVERY_REQUIRED".equals(source.status())) {
            throw ApiException.conflict("RECOVERY_NOT_REPLAYABLE",
                    "原 Outbox 已经终态收敛，不能再次重放。");
        }
        if (recovery.lockVersion() != expectedVersion) {
            throw WorkflowRecoverySupport.versionConflict(recovery.lockVersion());
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID attemptCorrelationId = UUID.randomUUID();
        int changed = markOutboxReplayingAndQueue(context.tenantId(), recovery,
                source, actorId, reason, attemptCorrelationId, now);
        if (changed != 1) {
            throw ApiException.conflict("RECOVERY_REPLAY_CONFLICT",
                    "该恢复记录的重放裁决已被其他请求取得。");
        }
        insertReplayRequestAudit(context.tenantId(), auditId, recoveryId,
                actorId, requestDigest, attemptCorrelationId, source.correlationId(), now);
        RecoveryFact accepted = findRecovery(context.tenantId(), recoveryId, false)
                .orElseThrow(WorkflowRecoveryAcceptanceServiceImpl::routeMismatch);
        return new Acceptance(WorkflowRecoveryViews.toView(accepted), false, null);
    }

    /**
     * 核对排队后的 Outbox 来源，确认原消息已进入可发送路径。
     */
    private static boolean activatedOutboxSource(
            OutboxReplaySource source) {
        return source.contractVersion() == 1 && java.util.Set.of(
                "flowdesk.workflow.instance-established.v1",
                "flowdesk.workflow.approval-finalized.v1",
                "flowdesk.workflow.notification-requested.v1").contains(source.contractRef());
    }

    /**
     * 校验既有重放锚点与当前身份及内容一致，防止复用键修改恢复意图。
     */
    private static void validateIdempotencyAnchor(
            AuditAnchor audit,
            UUID recoveryId,
            UUID actorId,
            String requestDigest,
            UUID sourceCorrelationId) {
        if (!"WORKFLOW_RECOVERY_REPLAY_REQUESTED".equals(audit.actionCode())
                || !"MESSAGE_RECOVERY".equals(audit.targetType())
                || !recoveryId.equals(audit.targetId())
                || !"SUCCEEDED".equals(audit.outcome())
                || !"USER".equals(audit.actorType())
                || !actorId.equals(audit.actorId())
                || !requestDigest.equals(audit.reason())
                || audit.errorCode() != null
                || audit.correlationId() == null
                || !Objects.equals(sourceCorrelationId, audit.causationId())) {
            throw ApiException.conflict(
                    "IDEMPOTENCY_KEY_REUSED",
                    "同一 Idempotency-Key 已用于不同的恢复重放意图。");
        }
    }

    /**
     * 构造统一的恢复路由不可见错误，避免暴露不属于当前入口的事实。
     */
    private static ApiException routeMismatch() {
        return ApiException.notFound(
                "RECOVERY_ROUTE_MISMATCH", "恢复记录不存在或不属于当前 Workflow 路由。");
    }

    /**
     * 按声明的租户与业务条件查询恢复记录，供调用方核对可见性、状态或幂等关系。
     */
    private Optional<RecoveryFact> findRecovery(UUID tenantId, UUID recoveryId, boolean lock) {
        List<RecoveryFact> rows = mapper.findRecovery(uuid(tenantId), uuid(recoveryId), lock);
        return rows.stream().findFirst();
    }
    /**
     * 按声明的租户与业务条件查询原始消息来源，供调用方核对可见性、状态或幂等关系。
     */
    private Optional<ReplaySource> findSource(UUID tenantId, UUID inboxId, boolean lock) {
        List<ReplaySource> rows = mapper.findSource(uuid(tenantId), uuid(inboxId), lock);
        return rows.stream().findFirst();
    }
    /**
     * 按声明的租户与业务条件查询原 Outbox 消息，供调用方核对可见性、状态或幂等关系。
     */
    private Optional<OutboxReplaySource> findOutboxSource(UUID tenantId, UUID outboxId, boolean lock) {
        return mapper.findOutboxSource(uuid(tenantId), uuid(outboxId), lock).stream().findFirst();
    }
    /**
     * 按声明的租户与业务条件查询审计记录，供调用方核对可见性、状态或幂等关系。
     */
    private Optional<AuditAnchor> findAudit(UUID tenantId, UUID auditId) {
        List<AuditAnchor> rows = mapper.findAudit(uuid(tenantId), uuid(auditId));
        return rows.stream().findFirst();
    }
    /**
     * 按状态与预期版本切换为重放中，持久化本次重放身份。
     */
    private int markReplaying(UUID tenantId,
                      RecoveryFact recovery,
                      UUID actorId,
                      String reason,
                      UUID attemptCorrelationId,
                      Instant now) {
        return mapper.markReplaying(Timestamp.from(RecoveryReplayResumer.leaseUntil(now)), Timestamp.from(now),
                uuid(actorId), reason, uuid(attemptCorrelationId), uuid(tenantId),
                uuid(recovery.recoveryId()), uuid(recovery.sourceRecordId()), recovery.lockVersion());
    }
    /**
     * 在同一事务登记重放并将原 Outbox 排队，防止请求已接纳但未继续投递。
     */
    private int markOutboxReplayingAndQueue(UUID tenantId, RecoveryFact recovery,
                                    OutboxReplaySource source, UUID actorId, String reason,
                                    UUID attemptCorrelationId, Instant now) {
        int recoveryChanged = mapper.markOutboxReplaying(Timestamp.from(now), uuid(actorId), reason, uuid(attemptCorrelationId),
                uuid(tenantId), uuid(recovery.recoveryId()), uuid(source.outboxId()),
                recovery.lockVersion());
        if (recoveryChanged != 1) return recoveryChanged;
        int outboxChanged = mapper.queueOutboxReplay(Timestamp.from(now), uuid(tenantId), uuid(source.outboxId()), source.lockVersion());
        if (outboxChanged != 1) {
            throw new IllegalStateException("Workflow Outbox replay source CAS was lost");
        }
        return 1;
    }
    /**
     * 持久化重放请求审计，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    private void insertReplayRequestAudit(UUID tenantId,
                                  UUID auditId,
                                  UUID recoveryId,
                                  UUID actorId,
                                  String requestDigest,
                                  UUID attemptCorrelationId,
                                  UUID sourceCorrelationId,
                                  Instant now) {
        insertAudit(tenantId, auditId, "WORKFLOW_RECOVERY_REPLAY_REQUESTED",
                recoveryId, "SUCCEEDED", actorId, requestDigest, null,
                attemptCorrelationId, sourceCorrelationId, now);
    }
    /**
     * 持久化审计记录，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    private void insertAudit(UUID tenantId,
                             UUID auditId,
                             String action,
                             UUID recoveryId,
                             String outcome,
                             UUID actorId,
                             String reason,
                             String errorCode,
                             UUID correlationId,
                             UUID causationId,
                             Instant now) {
        mapper.insertAudit(uuid(tenantId), uuid(auditId), action, uuid(recoveryId), outcome, uuid(actorId), reason,
                errorCode, uuid(correlationId), nullableUuid(causationId), Timestamp.from(now));
    }
    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
    /**
     * 转换可空 UUID，空值保持为空以适配可选数据库字段。
     */
    private static UUID nullableUuid(byte[] value) {
        return value == null ? null : uuid(value);
    }
    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
    /**
     * 转换可空 UUID，空值保持为空以适配可选数据库字段。
     */
    private static byte[] nullableUuid(UUID value) {
        return value == null ? null : uuid(value);
    }
}
