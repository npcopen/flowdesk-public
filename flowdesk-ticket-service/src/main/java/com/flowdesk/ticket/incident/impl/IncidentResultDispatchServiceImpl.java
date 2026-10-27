package com.flowdesk.ticket.incident.impl;

import com.flowdesk.ticket.incident.*;

import com.flowdesk.ticket.model.incident.result.*;
import com.flowdesk.ticket.persistence.Uuids;

import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.ticket.service.IdempotencyCodec;
import org.springframework.dao.DuplicateKeyException;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Incident 建单结果的独立 Outbox 仓储，仅领取 M7 权威结果消息并以租约限定完成权限。 */
public final class IncidentResultDispatchServiceImpl implements IncidentResultDispatchService {
    private static final int MAX_ATTEMPTS = IncidentMessageContract.OUTBOX_MAX_ATTEMPTS;
    private final IncidentResultOutboxPersistenceMapper mapper;

    /**
     * 构造告警建单组件并保存调用依赖。
     */
    public IncidentResultDispatchServiceImpl(IncidentResultOutboxPersistenceMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 查询到期或领取租约过期的发件箱候选，限制单批数量。
     */
    public List<Candidate> candidates(Instant now, Instant cutoff) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(cutoff, "cutoff");
        return mapper.candidatesSelect(IncidentMessageContract.ESTABLISHED_CONTRACT_REF, IncidentMessageContract.VERSION, MAX_ATTEMPTS, Timestamp.from(now), Timestamp.from(cutoff), IncidentMessageContract.OUTBOX_MAX_CLAIMS);
    }

    /**
     * 按当前状态和版本领取处理权，返回本次租约快照。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     */
    public Optional<Claimed> claim(Candidate candidate, String token, Instant now,
                                   Instant cutoff) {
        requireTx();
        requireTenant(candidate.tenantId());
        requireToken(token);
        int changed;
        if ("PENDING".equals(candidate.status())) {
            changed = mapper.claimUpdate(token, ts(now), ts(now), Uuids.toBytes(candidate.tenantId()), Uuids.toBytes(candidate.outboxId()), candidate.attemptCount(), MAX_ATTEMPTS, candidate.lockVersion(), ts(now));
        }
        else if ("PUBLISHING".equals(candidate.status()) && candidate.lockedBy() != null) {
            changed = mapper.claimUpdate2(token, ts(now), ts(now), Uuids.toBytes(candidate.tenantId()), Uuids.toBytes(candidate.outboxId()), candidate.attemptCount(), MAX_ATTEMPTS, candidate.lockedBy(), candidate.lockVersion(), ts(cutoff));
        }
        else {
            return Optional.empty();
        }
        if (changed != 1) return Optional.empty();
        return claimed(candidate.tenantId(), candidate.outboxId(), token,
                candidate.attemptCount() + 1, candidate.lockVersion() + 1);
    }

    /** 接管已耗尽尝试次数的消息，仅收敛恢复状态，不再消耗 Broker 发送次数。 */
    public Optional<Claimed> claimRecovery(Candidate candidate, String token, Instant now,
                                           Instant cutoff) {
        requireTx();
        requireTenant(candidate.tenantId());
        requireToken(token);
        if (candidate.attemptCount() != MAX_ATTEMPTS) return Optional.empty();
        int changed;
        if ("PENDING".equals(candidate.status())) {
            changed = mapper.claimRecoveryUpdate(token, ts(now), ts(now), Uuids.toBytes(candidate.tenantId()), Uuids.toBytes(candidate.outboxId()), MAX_ATTEMPTS, candidate.lockVersion(), ts(now));
        }
        else if ("PUBLISHING".equals(candidate.status()) && candidate.lockedBy() != null) {
            changed = mapper.claimRecoveryUpdate2(token, ts(now), ts(now), Uuids.toBytes(candidate.tenantId()), Uuids.toBytes(candidate.outboxId()), MAX_ATTEMPTS, candidate.lockedBy(), candidate.lockVersion(), ts(cutoff));
        }
        else {
            return Optional.empty();
        }
        if (changed != 1) return Optional.empty();
        return claimed(candidate.tenantId(), candidate.outboxId(), token,
                MAX_ATTEMPTS, candidate.lockVersion() + 1);
    }

    /**
     * 读取与领取令牌匹配的记录，供确认领取和后续结算。
     */
    private Optional<Claimed> claimed(UUID tenantId, UUID outboxId, String token,
                                      int attempt, long version) {
        return mapper.claimedSelect(Uuids.toBytes(tenantId), Uuids.toBytes(outboxId), attempt, token, version).stream().findFirst();
    }

    /**
     * 条件记录发布成功，并收敛关联恢复状态。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     */
    public boolean published(Claimed row, Instant now, Instant cutoff) {
        requireTx();
        requireTenant(row.tenantId());
        int changed = mapper.publishedUpdate(ts(now), ts(now), Uuids.toBytes(row.tenantId()), Uuids.toBytes(row.outboxId()), row.attemptCount(), row.token(), row.lockVersion(), ts(cutoff));
        if (changed != 1) return false;
        resolveReplayRecovery(row, now);
        return true;
    }

    /**
     * 原消息完成后收敛其重放恢复项，避免遗留 REPLAYING 状态。
     */
    private void resolveReplayRecovery(Claimed row, Instant now) {
        Optional<RecoveryState> recovery = findRecovery(row.tenantId(), row.outboxId(), true);
        if (recovery.isEmpty() || "RESOLVED".equals(recovery.get().state())) return;
        RecoveryState current = recovery.get();
        if (!Arrays.equals(row.intent(), current.intent()) || !"REPLAYING".equals(current.state())) {
            throw new IllegalStateException("Incident result Recovery state/content conflict");
        }
        int changed = mapper.resolveReplayRecoveryUpdate(ts(now), ts(now), Uuids.toBytes(row.tenantId()), Uuids.toBytes(current.recoveryId()), current.lockVersion());
        if (changed != 1) throw new IllegalStateException("Incident result Recovery resolution CAS lost");
    }

    /**
     * 在租约仍有效时安排重试时间，并保留本轮错误码。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     */
    public boolean pending(Claimed row, Instant now, Instant cutoff, Instant next, String code) {
        requireTx();
        requireTenant(row.tenantId());
        int changed = mapper.pendingUpdate(ts(next), safeCode(code), ts(now), Uuids.toBytes(row.tenantId()), Uuids.toBytes(row.outboxId()), row.attemptCount(), row.token(), row.lockVersion(), ts(cutoff));
        return changed == 1;
    }

    /** 将耗尽的第八次尝试转入具有确定性身份的恢复事实。 */
    public boolean recover(Claimed row, Instant now, Instant cutoff, String code) {
        requireTx();
        requireTenant(row.tenantId());
        if (row.attemptCount() != MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Incident result recovery requires the eighth attempt");
        }
        String safeCode = safeCode(code);
        int changed = mapper.recoverUpdate(ts(now), safeCode, ts(now), Uuids.toBytes(row.tenantId()), Uuids.toBytes(row.outboxId()), MAX_ATTEMPTS, row.token(), row.lockVersion(), ts(cutoff));
        if (changed != 1) return false;

        String summary = "Incident result publication exhausted bounded retries.";
        ensureRecovery(row, safeCode, summary, now);
        UUID auditId = stableUuid("FD-INCIDENT-RESULT-OUTBOX-RECOVERY-AUDIT-V1",
                row.tenantId(), row.outboxId(), safeCode);
        insertAuditOnce(row, auditId, safeCode, summary, now);
        return true;
    }

    /**
     * 查询或幂等建立恢复记录，已有记录必须与来源身份一致。
     */
    private void ensureRecovery(Claimed row, String code, String summary, Instant now) {
        UUID recoveryId = stableUuid("FD-INCIDENT-RESULT-OUTBOX-RECOVERY-V1",
                row.tenantId(), row.outboxId());
        Optional<RecoveryState> existing = findRecovery(row.tenantId(), row.outboxId(), true);
        if (existing.isPresent()) {
            RecoveryState state = existing.get();
            if (!recoveryId.equals(state.recoveryId()) || !Arrays.equals(row.intent(), state.intent())
                    || state.attemptCount() != MAX_ATTEMPTS
                    || !("OPEN".equals(state.state()) || "REPLAYING".equals(state.state()))
                    || !Objects.equals(code, state.errorCode())
                    || !Objects.equals(summary, state.summary())) {
                throw new IllegalStateException("Incident result Recovery identity/content conflict");
            }
            if ("REPLAYING".equals(state.state())) {
                int reopened = mapper.ensureRecoveryUpdate(code, summary, ts(now), Uuids.toBytes(row.tenantId()), Uuids.toBytes(recoveryId), state.lockVersion());
                if (reopened != 1) throw new IllegalStateException("Incident result Recovery reopen CAS lost");
            }
            return;
        }
        try {
            mapper.ensureRecoveryUpdate2(Uuids.toBytes(row.tenantId()), Uuids.toBytes(recoveryId), Uuids.toBytes(row.outboxId()), row.intent(), MAX_ATTEMPTS, code, summary, ts(now), ts(now), ts(now));
        }
        catch (DuplicateKeyException duplicate) {
            RecoveryState raced = findRecovery(row.tenantId(), row.outboxId(), true)
                    .orElseThrow(() -> duplicate);
            if (!recoveryId.equals(raced.recoveryId()) || !Arrays.equals(row.intent(), raced.intent())
                    || raced.attemptCount() != MAX_ATTEMPTS || !"OPEN".equals(raced.state())
                    || !Objects.equals(code, raced.errorCode())
                    || !Objects.equals(summary, raced.summary())) {
                throw new IllegalStateException("Incident result Recovery identity/content conflict", duplicate);
            }
        }
    }

    /**
     * 以稳定审计身份写入记录，重复执行时核验内容而不重复追加。
     */
    private void insertAuditOnce(Claimed row, UUID auditId, String code,
                                 String summary, Instant now) {
        Optional<AuditState> existing = findAudit(row.tenantId(), auditId);
        if (existing.isPresent()) {
            validateAudit(existing.get(), row, code, summary);
            return;
        }
        try {
            mapper.insertAuditOnceUpdate(Uuids.toBytes(row.tenantId()), Uuids.toBytes(auditId), Uuids.toBytes(row.outboxId()), summary, code, Uuids.toBytes(row.correlationId()), Uuids.toBytes(row.causationId()), ts(now));
        }
        catch (DuplicateKeyException duplicate) {
            AuditState raced = findAudit(row.tenantId(), auditId).orElseThrow(() -> duplicate);
            validateAudit(raced, row, code, summary);
        }
    }

    /**
     * 读取来源对应恢复记录，供去重、状态推进和审计检查。
     */
    private Optional<RecoveryState> findRecovery(UUID tenantId, UUID outboxId, boolean lock) {
        return mapper.findRecoverySelect(Uuids.toBytes(tenantId), Uuids.toBytes(outboxId), lock).stream().findFirst();
    }

    /**
     * 按稳定身份读取既有审计，供重复处理时核对业务内容。
     */
    private Optional<AuditState> findAudit(UUID tenantId, UUID auditId) {
        return mapper.findAuditSelect(Uuids.toBytes(tenantId), Uuids.toBytes(auditId))
                .stream().findFirst();
    }

    /**
     * 核对审计操作、目标和摘要，防止不同意图复用同一业务效果。
     */
    private static void validateAudit(AuditState audit, Claimed row, String code, String summary) {
        if (!"INCIDENT_RESULT_OUTBOX_RECOVERY_REQUIRED".equals(audit.action())
                || !"MESSAGE_RECOVERY".equals(audit.targetType())
                || !row.outboxId().equals(audit.targetId()) || !"FAILED".equals(audit.outcome())
                || !"SERVICE".equals(audit.actorType()) || audit.actorId() != null
                || !Objects.equals(summary, audit.reason()) || !Objects.equals(code, audit.errorCode())
                || !Objects.equals(row.correlationId(), audit.correlationId())
                || !Objects.equals(row.causationId(), audit.causationId())) {
            throw new IllegalStateException("Incident result Recovery audit identity/content conflict");
        }
    }

    /**
     * 从规范业务键派生稳定 UUID，使重试与恢复复用同一业务身份。
     */
    private static UUID stableUuid(String namespace, Object... fields) {
        return IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(namespace, fields));
    }

    /**
     * 将错误原因收敛为可保存的稳定错误码。
     */
    private static String safeCode(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_:.]{0,95}")
                ? value : "BROKER_RESULT_UNKNOWN";
    }

    /**
     * 校验领取令牌，避免缺少所有权身份时结算他人的任务。
     */
    private static void requireToken(String token) {
        if (token == null || token.isBlank() || token.length() > 96) {
            throw new IllegalArgumentException("Outbox claim token must be 1..96 characters");
        }
    }

    /**
     * 确认数据库事务已建立，避免恢复状态与业务记录分步提交。
     */
    private static void requireTx() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Incident result Outbox mutation requires a transaction");
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
    private static Timestamp ts(Instant value) {
        return Timestamp.from(Objects.requireNonNull(value, "timestamp"));
    }








}
