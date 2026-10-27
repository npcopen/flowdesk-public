package com.flowdesk.ticket.sla.impl;

import com.flowdesk.ticket.sla.*;

import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.timestamp;
import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.requireTransaction;
import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.requireTenant;
import java.util.List;
import java.sql.Timestamp;
import com.flowdesk.ticket.model.sla.*;
import com.flowdesk.ticket.mapper.*;
import com.flowdesk.ticket.model.*;
import com.flowdesk.ticket.persistence.Uuids;

import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.ticket.iam.SlaTargetQualificationChecker;

import com.flowdesk.ticket.service.IdempotencyCodec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 受控维护工单侧具体 SLA 升级通知对象的服务端配置端口。
 * 先在写事务外向 IAM 校验资格，再锁定映射，原子更新目标、资格快照和确定性审计事实。
 */
@Service
public class SlaTargetProvisioningServiceImpl implements SlaTargetProvisioningService {
    private static final String PURPOSE = "SLA";
    private static final String ACTOR_TYPE = "SERVICE";
    private static final String UNKNOWN_REASON = "IAM_RESULT_INVALID";

    private final SlaTargetPersistenceMapper mapper;
    private final TicketAuditMapper ticketAuditMapper;
    private final SlaTargetQualificationChecker qualificationChecker;
    private final TicketSlaProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 构造SLA组件并保存调用依赖；支持注入时钟以控制时间边界。
     */
    public SlaTargetProvisioningServiceImpl(SlaTargetPersistenceMapper mapper,
                                        TicketAuditMapper ticketAuditMapper,
                                        SlaTargetQualificationChecker qualificationChecker,
                                        TicketSlaProperties properties,
                                        PlatformTransactionManager transactionManager,
                                        Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.ticketAuditMapper = Objects.requireNonNull(ticketAuditMapper, "ticketRepository");
        this.qualificationChecker = Objects.requireNonNull(qualificationChecker,
                "qualificationChecker");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager,
                "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 创建该租户第一个有效的升级通知目标。 */
    public ProvisionResult create(UUID tenantId, UUID targetId, UUID recipientUserId,
                                  Instant effectiveAt) {
        return create(tenantId, targetId, recipientUserId, effectiveAt, null);
    }

    /** 显式指定配置操作者，供审计元数据记录。 */
    public ProvisionResult create(UUID tenantId, UUID targetId, UUID recipientUserId,
                                  Instant effectiveAt, UUID actorId) {
        requireTenant(tenantId);
        Inputs inputs = inputs(tenantId, targetId, recipientUserId, effectiveAt);
        QualificationSnapshot snapshot = qualify(tenantId, recipientUserId);
        // 仅配置入口可将具体用户提升为 ACTIVE SLA 映射。
        // IAM 否定结果可将已有映射刷新为 DEGRADED，但不能据此替换正常目标或创建可启用的新映射。
        if (!snapshot.writable() || !snapshot.eligible()) {
            return new ProvisionResult(tenantId, targetId, false, false, snapshot.code());
        }
        try {
            return transactions.execute(tx -> createInTransaction(inputs, snapshot, actorId));
        }
        catch (DuplicateKeyException duplicate) {
            return new ProvisionResult(tenantId, targetId, false, false,
                    "SLA_TARGET_ACTIVE_EXISTS");
        }
    }

    /** 替换当前有效目标，同时保留旧映射作为历史。 */
    public ProvisionResult replace(UUID tenantId, UUID newTargetId, UUID recipientUserId,
                                   Instant effectiveAt) {
        return replace(tenantId, null, newTargetId, recipientUserId, effectiveAt, null);
    }

    /** 替换指定的旧目标；携带旧身份使并发替换后的操作员重试保守失败，避免误替换新目标。 */
    public ProvisionResult replace(UUID tenantId, UUID oldTargetId, UUID newTargetId,
                                   UUID recipientUserId, Instant effectiveAt, UUID actorId) {
        requireTenant(tenantId);
        Inputs inputs = inputs(tenantId, newTargetId, recipientUserId, effectiveAt);
        QualificationSnapshot snapshot = qualify(tenantId, recipientUserId);
        if (!snapshot.writable() || !snapshot.eligible()) {
            return new ProvisionResult(tenantId, newTargetId, false, false, snapshot.code());
        }
        try {
            return transactions.execute(tx -> replaceInTransaction(tenantId, oldTargetId,
                    inputs, snapshot, actorId));
        }
        catch (DuplicateKeyException duplicate) {
            return new ProvisionResult(tenantId, newTargetId, false, false,
                    "SLA_TARGET_ACTIVE_EXISTS");
        }
    }

    /** 为不携带操作者标识的受控调用方提供便捷重载。 */
    public ProvisionResult replace(UUID tenantId, UUID oldTargetId, UUID newTargetId,
                                   UUID recipientUserId, Instant effectiveAt) {
        return replace(tenantId, oldTargetId, newTargetId, recipientUserId, effectiveAt, null);
    }

    /** 停用目标，保留历史映射记录。 */
    public ProvisionResult deactivate(UUID tenantId, UUID targetId) {
        return deactivate(tenantId, targetId, null);
    }

    /**
     * 停用当前升级目标，并记录原因与操作审计。
     * 传入租户必须与当前上下文一致。
     */
    public ProvisionResult deactivate(UUID tenantId, UUID targetId, UUID actorId) {
        requireTenant(tenantId);
        if (targetId == null) {
            return new ProvisionResult(tenantId, null, false, false, "TARGET_ID_REQUIRED");
        }
        return transactions.execute(tx -> {
            com.flowdesk.ticket.model.sla.SlaEscalationTargetRow target = findSlaEscalationTarget(tenantId, targetId, true).orElse(null);
            if (target == null) {
                return new ProvisionResult(tenantId, targetId, false, false, "TARGET_NOT_FOUND");
            }
            if (!PURPOSE.equals(target.purpose())) {
                return new ProvisionResult(tenantId, targetId, false, false,
                        "SLA_TARGET_PURPOSE_INVALID");
            }
            if ("INACTIVE".equals(target.status())) {
                return new ProvisionResult(tenantId, targetId, true, true, "ALREADY_INACTIVE");
            }
            Instant at = now();
            int changed = deactivateSlaEscalationTarget(tenantId, targetId,
                    target.lockVersion(), ACTOR_TYPE, actorId, at);
            if (changed != 1) {
                throw new IllegalStateException("SLA target deactivate CAS changed " + changed
                        + " rows");
            }
            appendAudit(tenantId, targetId, "SLA_TARGET_DEACTIVATED", "DEACTIVATED",
                    "SLA target deactivated", actorId, at);
            return new ProvisionResult(tenantId, targetId, true, false, "DEACTIVATED");
        });
    }

    /**
     * 在同一事务创建升级目标、资格快照与配置审计。
     */
    private ProvisionResult createInTransaction(Inputs inputs,
                                               QualificationSnapshot snapshot,
                                               UUID actorId) {
        Optional<com.flowdesk.ticket.model.sla.SlaEscalationTargetRow> active = findActiveSlaEscalationTarget(inputs.tenantId(), true);
        if (active.isPresent()) {
            com.flowdesk.ticket.model.sla.SlaEscalationTargetRow row = active.get();
            if (row.targetId().equals(inputs.targetId())
                    && row.recipientUserId().equals(inputs.recipientUserId())
                    && inputs.effectiveAt().equals(row.effectiveAt())) {
                return new ProvisionResult(inputs.tenantId(), inputs.targetId(), true, true,
                        "ALREADY_PROVISIONED");
            }
            return new ProvisionResult(inputs.tenantId(), inputs.targetId(), false, false,
                    "SLA_TARGET_ACTIVE_EXISTS");
        }
        Instant at = now();
        insertSlaEscalationTarget(inputs.tenantId(), inputs.targetId(),
                inputs.recipientUserId(), PURPOSE, "ACTIVE", inputs.effectiveAt(),
                snapshot.version(), snapshot.checkedAt(), snapshot.validUntil(), snapshot.state(),
                snapshot.reasonCode(), ACTOR_TYPE, actorId, at);
        upsertQualificationSnapshot(inputs.tenantId(), inputs.targetId(),
                inputs.recipientUserId(), snapshot.version(), snapshot.checkedAt(),
                snapshot.validUntil(), snapshot.eligible(), snapshot.reasonCode(), at);
        appendAudit(inputs.tenantId(), inputs.targetId(), "SLA_TARGET_PROVISIONED", "SUCCEEDED",
                snapshot.reasonCode(), actorId, at);
        return new ProvisionResult(inputs.tenantId(), inputs.targetId(), true, false,
                snapshot.eligible() ? "PROVISIONED" : "PROVISIONED_DEGRADED");
    }

    /**
     * 原子停用旧目标并保存新目标和快照，避免部分替换。
     */
    private ProvisionResult replaceInTransaction(UUID tenantId, UUID expectedOldTargetId,
                                                 Inputs inputs,
                                                 QualificationSnapshot snapshot,
                                                 UUID actorId) {
        com.flowdesk.ticket.model.sla.SlaEscalationTargetRow active = findActiveSlaEscalationTarget(tenantId, true).orElse(null);
        if (active == null) {
            if (expectedOldTargetId != null) {
                return new ProvisionResult(tenantId, inputs.targetId(), false, false,
                        "SLA_TARGET_REPLACEMENT_STALE");
            }
            return createInTransaction(inputs, snapshot, actorId);
        }
        if (expectedOldTargetId != null && !expectedOldTargetId.equals(active.targetId())) {
            return new ProvisionResult(tenantId, inputs.targetId(), false, false,
                    "SLA_TARGET_REPLACEMENT_STALE");
        }
        if (active.targetId().equals(inputs.targetId())) {
            if (active.recipientUserId().equals(inputs.recipientUserId())
                    && active.effectiveAt().equals(inputs.effectiveAt())) {
                return new ProvisionResult(tenantId, inputs.targetId(), true, true,
                        "ALREADY_PROVISIONED");
            }
            return new ProvisionResult(tenantId, inputs.targetId(), false, false,
                    "SLA_TARGET_ID_REUSE_CONFLICT");
        }

        // 有效行已被锁定；先停用再插入替代项，避免违反唯一 ACTIVE 映射约束。
        Instant at = now();
        int deactivated = deactivateSlaEscalationTarget(tenantId,
                active.targetId(), active.lockVersion(), ACTOR_TYPE, actorId, at);
        if (deactivated != 1) {
            throw new IllegalStateException("SLA target replacement lost old-row lock");
        }
        insertSlaEscalationTarget(inputs.tenantId(), inputs.targetId(),
                inputs.recipientUserId(), PURPOSE, "ACTIVE", inputs.effectiveAt(),
                snapshot.version(), snapshot.checkedAt(), snapshot.validUntil(), snapshot.state(),
                snapshot.reasonCode(), ACTOR_TYPE, actorId, at);
        upsertQualificationSnapshot(inputs.tenantId(), inputs.targetId(),
                inputs.recipientUserId(), snapshot.version(), snapshot.checkedAt(),
                snapshot.validUntil(), snapshot.eligible(), snapshot.reasonCode(), at);
        appendAudit(tenantId, active.targetId(), "SLA_TARGET_REPLACED_FROM", "SUCCEEDED",
                inputs.targetId().toString(), actorId, at);
        appendAudit(tenantId, inputs.targetId(), "SLA_TARGET_REPLACED", "SUCCEEDED",
                snapshot.reasonCode(), actorId, at);
        return new ProvisionResult(tenantId, inputs.targetId(), true, false,
                snapshot.eligible() ? "REPLACED" : "REPLACED_DEGRADED");
    }

    /**
     * 在配置写事务前查询 IAM 资格，避免持有事务等待远程响应。
     */
    private QualificationSnapshot qualify(UUID tenantId, UUID recipientUserId) {
        final SlaTargetQualificationChecker.Qualification result;
        try {
            result = qualificationChecker.checkServiceDeskAdmin(tenantId, recipientUserId);
        }
        catch (RuntimeException unavailable) {
            return QualificationSnapshot.unwritable("IAM_UNAVAILABLE");
        }
        Instant at = now();
        if (result == null || result.subjectUserId() == null
                || !recipientUserId.equals(result.subjectUserId())
                || result.qualificationVersion() <= 0 || result.checkedAt() == null
                || !result.checkedAt().equals(result.checkedAt().truncatedTo(ChronoUnit.MICROS))
                || result.checkedAt().isBefore(at.minus(properties.getQualificationMaxAge()))
                || result.checkedAt().isAfter(at.plus(properties.getQualificationClockSkew()))
                || !validReason(result.reasonCode())
                || result.eligible() != "ELIGIBLE".equals(result.reasonCode())) {
            return QualificationSnapshot.unwritable(UNKNOWN_REASON);
        }
        Instant checked = result.checkedAt().truncatedTo(ChronoUnit.MICROS);
        Instant validUntil = checked.plus(properties.getQualificationMaxAge())
                .truncatedTo(ChronoUnit.MICROS);
        boolean eligible = result.eligible() && "ELIGIBLE".equals(result.reasonCode());
        return new QualificationSnapshot(true, eligible,
                eligible ? "ELIGIBLE" : "DEGRADED", safeReason(result.reasonCode()),
                result.qualificationVersion(), checked, validUntil,
                eligible ? "QUALIFIED" : "INELIGIBLE");
    }

    /**
     * 记录升级目标配置审计，保留操作人、原因与变更结果。
     * 唯一约束竞争时回读既有事实并核验内容，避免重复产生业务效果。
     */
    private void appendAudit(UUID tenantId, UUID targetId, String action, String outcome,
                             String reason, UUID actorId, Instant at) {
        UUID auditId = IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(
                "FD-SLA-TARGET-PROVISIONING-AUDIT-V1", tenantId, targetId, action,
                reason == null ? "" : reason));
        Optional<AuditRow> existing = ticketAuditMapper.findAudit(tenantId, auditId);
        if (existing.isPresent()) {
            AuditRow row = existing.get();
            if (!Objects.equals(row.actionCode(), action)
                    || !Objects.equals(row.targetType(), "SLA_ESCALATION_TARGET")
                    || !Objects.equals(row.targetId(), targetId)
                    || !Objects.equals(row.outcome(), outcome)
                    || !Objects.equals(row.actorType(), ACTOR_TYPE)
                    || !Objects.equals(row.actorId(), actorId)
                    || !Objects.equals(row.reason(), reason)) {
                throw new IllegalStateException("SLA target provisioning audit conflict");
            }
            return;
        }
        try {
            UUID correlationId = TenantContextHolder.current().correlationId();
            ticketAuditMapper.insertAudit(tenantId, auditId, action, "SLA_ESCALATION_TARGET", targetId,
                    outcome, ACTOR_TYPE, actorId, reason, null, correlationId, null, at, at);
        }
        catch (DuplicateKeyException duplicate) {
            AuditRow row = ticketAuditMapper.findAudit(tenantId, auditId)
                    .orElseThrow(() -> duplicate);
            if (!Objects.equals(row.actionCode(), action)
                    || !Objects.equals(row.targetId(), targetId)
                    || !Objects.equals(row.reason(), reason)) {
                throw new IllegalStateException("SLA target provisioning audit conflict", duplicate);
            }
        }
    }

    /**
     * 校验并规范化配置请求，生成可供事务写入的输入快照。
     */
    private Inputs inputs(UUID tenantId, UUID targetId, UUID recipientUserId,
                          Instant effectiveAt) {
        if (tenantId == null || targetId == null || recipientUserId == null || effectiveAt == null
                || isZero(targetId) || isZero(recipientUserId)) {
            throw new IllegalArgumentException("SLA target provisioning values are incomplete");
        }
        return new Inputs(tenantId, targetId, recipientUserId,
                effectiveAt.truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * 检查操作原因是否满足格式及长度约束。
     */
    private static boolean validReason(String reason) {
        return reason != null && reason.matches("[A-Z][A-Z0-9_.:-]{0,95}");
    }

    /**
     * 清理原因文本并限制长度，供审计与恢复信息保存。
     */
    private static String safeReason(String reason) {
        return validReason(reason) ? reason : UNKNOWN_REASON;
    }

    /**
     * 取得当前时间并规范为业务使用的时间精度。
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 校验传入租户与当前上下文一致，阻止跨租户持久化操作。
     */
    private static void requireTenant(UUID tenantId) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId is required");
        TenantContextHolder.current().requireTenant(tenantId);
    }

    /**
     * 判断 UUID 是否为全零占位值，避免误用为真实业务身份。
     */
    private static boolean isZero(UUID value) {
        return value != null && value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L;
    }

    /**
     * 已校验目标配置输入，确保远程预检与事务写入使用同一内容。
     */
    private record Inputs(UUID tenantId, UUID targetId, UUID recipientUserId, Instant effectiveAt) { }

    /**
     * 配置写入使用的资格快照，保留目标、规则版本及有效期。
     */
    private record QualificationSnapshot(boolean writable, boolean eligible, String state,
                                        String reasonCode, long version, Instant checkedAt,
                                        Instant validUntil, String code) {
        /**
         * 生成不可写资格结果，阻止无效或无法确认的资格被启用。
         */
        private static QualificationSnapshot unwritable(String code) {
            return new QualificationSnapshot(false, false, "DEGRADED", safeReason(code),
                    1L, Instant.EPOCH, Instant.EPOCH, code);
        }
    }



    /**
     * 读取租户启用的升级接收目标，供资格校验和通知处理。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     */
    private Optional<SlaEscalationTargetRow> findActiveSlaEscalationTarget(UUID tenantId,
                                                                           boolean lock) {
        requireTenant(tenantId);
        if (lock) requireTransaction();
        return mapper.findActiveSlaEscalationTargetSelect(Uuids.toBytes(tenantId), lock).stream().findFirst();
    }

    /**
     * 读取指定升级目标，供更新、替换与审计核对。
     * 传入租户必须与当前上下文一致。
     */
    private Optional<SlaEscalationTargetRow> findSlaEscalationTarget(UUID tenantId,
                                                                     UUID targetId,
                                                                     boolean lock) {
        requireTenant(tenantId);
        if (targetId == null) {
            return Optional.empty();
        }
        return mapper.findSlaEscalationTargetSelect(Uuids.toBytes(tenantId), Uuids.toBytes(targetId), lock).stream().findFirst();
    }

    /**
     * 保存升级目标，记录配置操作人和生效信息。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 传入租户必须与当前上下文一致。
     */
    private int insertSlaEscalationTarget(UUID tenantId, UUID targetId,
                                         UUID recipientUserId, String purpose,
                                         String status, Instant effectiveAt,
                                         Long qualificationVersion,
                                         Instant qualificationCheckedAt,
                                         Instant qualificationValidUntil,
                                         String qualificationState,
                                         String qualificationReasonCode,
                                         String actorType, UUID actorId, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        if (targetId == null || recipientUserId == null || effectiveAt == null
                || purpose == null || status == null || qualificationState == null
                || actorType == null || now == null) {
            throw new IllegalArgumentException("SLA target insert values are incomplete");
        }
        return mapper.insertSlaEscalationTargetUpdate(Uuids.toBytes(tenantId), Uuids.toBytes(targetId), purpose, Uuids.toBytes(recipientUserId), status, Timestamp.from(effectiveAt), qualificationVersion, timestamp(qualificationCheckedAt), timestamp(qualificationValidUntil), qualificationState, qualificationReasonCode, Timestamp.from(now), actorType, Uuids.toBytes(actorId), Timestamp.from(now), actorType, Uuids.toBytes(actorId));
    }

    /**
     * 停用指定升级目标，后续新触发不再选用该配置。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 传入租户必须与当前上下文一致。
     */
    private int deactivateSlaEscalationTarget(UUID tenantId, UUID targetId,
                                             long expectedLockVersion,
                                             String actorType, UUID actorId, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        return mapper.deactivateSlaEscalationTargetUpdate(Timestamp.from(now), actorType, Uuids.toBytes(actorId), Uuids.toBytes(tenantId), Uuids.toBytes(targetId), expectedLockVersion);
    }

    /**
     * 保存资格快照及其版本、有效时间，供后续离线检查。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 传入租户必须与当前上下文一致。
     */
    private int upsertQualificationSnapshot(UUID tenantId, UUID targetId, UUID recipientUserId,
                                           long qualificationVersion, Instant checkedAt,
                                           Instant validUntil, boolean eligible,
                                           String reasonCode, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        return mapper.upsertQualificationSnapshotUpdate(Uuids.toBytes(tenantId), Uuids.toBytes(targetId), Uuids.toBytes(recipientUserId), qualificationVersion, Timestamp.from(checkedAt), Timestamp.from(validUntil), eligible, reasonCode, Timestamp.from(now));
    }
}
