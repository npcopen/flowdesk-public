package com.flowdesk.workflow.service.recovery.impl;

import com.flowdesk.workflow.start.*;
import com.flowdesk.workflow.model.recovery.WorkflowRecoveryModels.*;
import com.flowdesk.workflow.service.recovery.*;
import com.flowdesk.workflow.dto.*;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.temporal.ChronoUnit;

/**
 * 结算一次人工重放结果；只更新当前尝试对应的恢复状态和审计，不改写原始消息事实。
 */
public final class WorkflowRecoveryExecutionServiceImpl implements WorkflowRecoveryExecutionService {

    private static final String FAILURE_SUMMARY =
            "The controlled same-path replay did not reach a terminal local decision.";

    private final WorkflowRecoveryMapper mapper;
    private final TransactionTemplate transactions;

    /**
     * 注入恢复映射及事务管理器，核验已提交的重放身份后再结算成功或失败，防止过期工作者覆盖新租约。
     */
    public WorkflowRecoveryExecutionServiceImpl(WorkflowRecoveryMapper mapper,
                                     PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 按已提交工作项和消费结果结算恢复，只允许本次持有者修改相关状态。
     */
    public void completed(ReplayWork work,
                   WorkflowStartConsumeResult result) {
        transactions.executeWithoutResult(status -> {
            ReplaySource source = findSource(work.tenantId(), work.sourceRecordId(), true)
                    .orElse(null);
            RecoveryFact recovery = findRecovery(work.tenantId(), work.recoveryId(), true)
                    .orElse(null);
            if (recovery == null || !work.attemptCorrelationId().equals(recovery.lastReplayCorrelationId())) return;
            if (source == null || !("PROCESSED".equals(source.status())
                    || "REJECTED".equals(source.status()))) {
                reopenInside(work, "RECOVERY_REPLAY_NOT_TERMINAL");
                return;
            }
            if ("REPLAYING".equals(recovery.state()) && work.leaseUntil().equals(recovery.nextAttemptAt())) {
                resolveTerminal(work.tenantId(), work.recoveryId(), work.attemptCorrelationId(),
                        work.leaseUntil(), now());
            } else if (!"RESOLVED".equals(recovery.state())) return;
            String outcome = "PROCESSED".equals(source.status()) ? "SUCCEEDED" : "REJECTED";
            insertReplayOutcomeAudit(
                    work.tenantId(),
                    WorkflowRecoveryIdempotency.outcomeAuditId(
                            work.tenantId(), work.attemptCorrelationId(), outcome),
                    work.recoveryId(), work.actorId(), outcome, work.reason(),
                    "REJECTED".equals(outcome) ? result.code() : null,
                    work.attemptCorrelationId(), work.sourceCorrelationId(), now());
        });
    }

    /**
     * 保存本次仍持有的重放失败结果，过期持有者不能重新开放新租约。
     */
    public void failed(ReplayWork work, String errorCode) {
        transactions.executeWithoutResult(status -> reopenInside(work, errorCode));
    }

    /**
     * 事务内核验本次重放身份，再开放失败记录并补写结果审计。
     */
    private void reopenInside(ReplayWork work,
                              String errorCode) {
        Instant now = now();
        // 按尝试身份与租约条件更新；失去所有权的旧任务不得把新任务状态重新打开。
        int changed = reopenFailedReplay(
                work.tenantId(), work.recoveryId(), work.attemptCorrelationId(), work.leaseUntil(),
                errorCode, FAILURE_SUMMARY, now);
        if (changed == 1) {
            insertReplayOutcomeAudit(
                    work.tenantId(),
                    WorkflowRecoveryIdempotency.outcomeAuditId(
                            work.tenantId(), work.attemptCorrelationId(), "FAILED"),
                    work.recoveryId(), work.actorId(), "FAILED", work.reason(), errorCode,
                    work.attemptCorrelationId(), work.sourceCorrelationId(), now);
        }
    }

    /**
     * 读取注入时钟并截断至数据库可保存的精度，便于持久化比较及测试。
     */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 事务内核验当前租约并读取原信封，来源已终态则先结清恢复而不再次消费。
     */
    public Optional<ReplaySource> currentSource(ReplayWork work) {
        RecoveryFact recovery = findRecovery(work.tenantId(), work.recoveryId(), false).orElse(null);
        ReplaySource source = findSource(work.tenantId(), work.sourceRecordId(), false).orElse(null);
        if (recovery == null || source == null
                || !source.tenantId().equals(work.tenantId())
                || !recovery.sourceRecordId().equals(source.inboxId())
                || !"INBOX".equals(recovery.direction()) || !"REPLAYING".equals(recovery.state())
                || !work.attemptCorrelationId().equals(recovery.lastReplayCorrelationId())
                || !work.leaseUntil().equals(recovery.nextAttemptAt())) return Optional.empty();
        return Optional.of(source);
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
     * 按声明的租户与业务条件查询审计记录，供调用方核对可见性、状态或幂等关系。
     */
    private Optional<AuditAnchor> findAudit(UUID tenantId, UUID auditId) {
        List<AuditAnchor> rows = mapper.findAudit(uuid(tenantId), uuid(auditId));
        return rows.stream().findFirst();
    }
    /**
     * 按当前重放身份结清终态来源的恢复记录，防止终态回退。
     */
    private int resolveTerminal(UUID tenantId, UUID recoveryId, UUID attemptCorrelationId,
                        Instant leaseUntil, Instant now) {
        return mapper.resolveTerminal(Timestamp.from(now), uuid(tenantId), uuid(recoveryId), uuid(attemptCorrelationId),
                Timestamp.from(leaseUntil));
    }
    /**
     * 只对本次仍持有的重放撤销执行中状态，防止覆盖后续新租约。
     */
    private int reopenFailedReplay(UUID tenantId,
                           UUID recoveryId,
                           UUID attemptCorrelationId,
                           Instant leaseUntil,
                           String errorCode,
                           String errorSummary,
                           Instant now) {
        return mapper.reopenFailedReplay(errorCode, errorSummary, Timestamp.from(now), uuid(tenantId), uuid(recoveryId),
                uuid(attemptCorrelationId), Timestamp.from(leaseUntil));
    }
    /**
     * 持久化重放结果审计，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    private void insertReplayOutcomeAudit(UUID tenantId,
                                  UUID auditId,
                                  UUID recoveryId,
                                  UUID actorId,
                                  String outcome,
                                  String reason,
                                  String errorCode,
                                  UUID attemptCorrelationId,
                                  UUID sourceCorrelationId,
                                  Instant now) {
        if (findAudit(tenantId, auditId).isPresent()) {
            return;
        }
        insertAudit(tenantId, auditId, "WORKFLOW_RECOVERY_REPLAY_COMPLETED",
                recoveryId, outcome, actorId, reason, errorCode,
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
