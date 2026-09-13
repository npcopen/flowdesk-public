package com.flowdesk.workflow.start;

import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.web.ApiException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** API-060 acceptance transaction; it records work but never executes business processing. */
final class WorkflowRecoveryReplayTransaction {

    private final WorkflowRecoveryRepository repository;
    private final TransactionTemplate transactions;

    WorkflowRecoveryReplayTransaction(WorkflowRecoveryRepository repository,
                                      PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    Acceptance accept(TenantContext context,
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

    private Acceptance acceptInside(TenantContext context,
                                    UUID recoveryId,
                                    UUID actorId,
                                    String reason,
                                    long expectedVersion,
                                    UUID auditId,
                                    String requestDigest) {
        WorkflowRecoveryRepository.RecoveryFact candidate = repository
                .findRecovery(context.tenantId(), recoveryId, false)
                .orElseThrow(WorkflowRecoveryReplayTransaction::routeMismatch);
        if ("OUTBOX".equals(candidate.direction())) {
            return acceptOutboxInside(context, recoveryId, actorId, reason, expectedVersion,
                    auditId, requestDigest, candidate);
        }
        if (!"INBOX".equals(candidate.direction())) {
            throw routeMismatch();
        }
        WorkflowRecoveryRepository.ReplaySource source = repository
                .findSource(context.tenantId(), candidate.sourceRecordId(), true)
                .orElseThrow(WorkflowRecoveryReplayTransaction::routeMismatch);
        WorkflowRecoveryRepository.RecoveryFact recovery = repository
                .findRecovery(context.tenantId(), recoveryId, true)
                .filter(fact -> "INBOX".equals(fact.direction())
                        && fact.sourceRecordId().equals(source.inboxId()))
                .orElseThrow(WorkflowRecoveryReplayTransaction::routeMismatch);

        var prior = repository.findAudit(context.tenantId(), auditId);
        if (prior.isPresent()) {
            validateIdempotencyAnchor(prior.get(), recoveryId, actorId,
                    requestDigest, source.correlationId());
            return new Acceptance(
                    WorkflowRecoveryRepository.toView(recovery), true, null);
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
        int changed = repository.markReplaying(context.tenantId(), recovery, actorId,
                reason, attemptCorrelationId, now);
        if (changed != 1) {
            throw ApiException.conflict(
                    "RECOVERY_REPLAY_CONFLICT", "该恢复记录的重放裁决已被其他请求取得。");
        }
        repository.insertReplayRequestAudit(context.tenantId(), auditId, recoveryId,
                actorId, requestDigest, attemptCorrelationId, source.correlationId(), now);
        WorkflowRecoveryRepository.RecoveryFact accepted = repository
                .findRecovery(context.tenantId(), recoveryId, false)
                .orElseThrow(WorkflowRecoveryReplayTransaction::routeMismatch);
        ReplayWork work = new ReplayWork(
                context.tenantId(), recoveryId, recovery.sourceRecordId(), actorId,
                reason, attemptCorrelationId, source.correlationId());
        return new Acceptance(WorkflowRecoveryRepository.toView(accepted), false, work);
    }

    private Acceptance acceptOutboxInside(TenantContext context,
                                           UUID recoveryId,
                                           UUID actorId,
                                           String reason,
                                           long expectedVersion,
                                           UUID auditId,
                                           String requestDigest,
                                           WorkflowRecoveryRepository.RecoveryFact candidate) {
        WorkflowRecoveryRepository.OutboxReplaySource source = repository
                .findOutboxSource(context.tenantId(), candidate.sourceRecordId(), true)
                .filter(WorkflowRecoveryReplayTransaction::activatedOutboxSource)
                .orElseThrow(WorkflowRecoveryReplayTransaction::routeMismatch);
        WorkflowRecoveryRepository.RecoveryFact recovery = repository
                .findRecovery(context.tenantId(), recoveryId, true)
                .filter(fact -> "OUTBOX".equals(fact.direction())
                        && fact.sourceRecordId().equals(source.outboxId()))
                .orElseThrow(WorkflowRecoveryReplayTransaction::routeMismatch);
        var prior = repository.findAudit(context.tenantId(), auditId);
        if (prior.isPresent()) {
            validateIdempotencyAnchor(prior.get(), recoveryId, actorId,
                    requestDigest, source.correlationId());
            return new Acceptance(WorkflowRecoveryRepository.toView(recovery), true, null);
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
        int changed = repository.markOutboxReplayingAndQueue(context.tenantId(), recovery,
                source, actorId, reason, attemptCorrelationId, now);
        if (changed != 1) {
            throw ApiException.conflict("RECOVERY_REPLAY_CONFLICT",
                    "该恢复记录的重放裁决已被其他请求取得。");
        }
        repository.insertReplayRequestAudit(context.tenantId(), auditId, recoveryId,
                actorId, requestDigest, attemptCorrelationId, source.correlationId(), now);
        WorkflowRecoveryRepository.RecoveryFact accepted = repository
                .findRecovery(context.tenantId(), recoveryId, false)
                .orElseThrow(WorkflowRecoveryReplayTransaction::routeMismatch);
        return new Acceptance(WorkflowRecoveryRepository.toView(accepted), false, null);
    }

    private static boolean activatedOutboxSource(
            WorkflowRecoveryRepository.OutboxReplaySource source) {
        return source.contractVersion() == 1 && java.util.Set.of(
                "flowdesk.workflow.instance-established.v1",
                "flowdesk.workflow.approval-finalized.v1").contains(source.contractRef());
    }

    private static void validateIdempotencyAnchor(
            WorkflowRecoveryRepository.AuditAnchor audit,
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

    private static ApiException routeMismatch() {
        return ApiException.notFound(
                "RECOVERY_ROUTE_MISMATCH", "恢复记录不存在或不属于当前 Workflow 路由。");
    }

    record Acceptance(WorkflowRecoveryView recovery,
                      boolean replayed,
                      ReplayWork work) {
    }

    record ReplayWork(UUID tenantId,
                      UUID recoveryId,
                      UUID sourceRecordId,
                      UUID actorId,
                      String reason,
                      UUID attemptCorrelationId,
                      UUID sourceCorrelationId) {
    }
}
