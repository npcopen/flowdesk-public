package com.flowdesk.ticket.sla.impl;

import com.flowdesk.ticket.sla.*;

import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.timestamp;
import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.requireTransaction;
import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.requireTenant;
import java.util.Optional;
import java.sql.Timestamp;
import com.flowdesk.ticket.model.sla.*;
import com.flowdesk.ticket.mapper.*;
import com.flowdesk.ticket.model.*;
import com.flowdesk.ticket.persistence.Uuids;

import com.flowdesk.ticket.iam.SlaTargetQualificationChecker;

import com.flowdesk.ticket.service.IdempotencyCodec;
import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.common.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 维护工单侧具体 SLA 通知对象的资格快照。
 * IAM 调用位于工单写事务之外，本地只持久化不可变校验结果及较短的有效窗口。
 */
@Service
public class SlaTargetQualificationServiceImpl implements SlaTargetQualificationService {
    private static final Logger LOG = LoggerFactory.getLogger(SlaTargetQualificationService.class);

    private final SlaTargetPersistenceMapper mapper;
    private final TicketAuditMapper ticketAuditMapper;
    private final SlaTargetQualificationChecker checker;
    private final TicketSlaProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 构造SLA组件并保存调用依赖；支持注入时钟以控制时间边界。
     */
    public SlaTargetQualificationServiceImpl(SlaTargetPersistenceMapper mapper,
                                          TicketAuditMapper ticketAuditMapper,
                                          SlaTargetQualificationChecker checker,
                                          TicketSlaProperties properties,
                                          PlatformTransactionManager transactionManager,
                                          Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.ticketAuditMapper = Objects.requireNonNull(ticketAuditMapper, "ticketRepository");
        this.checker = Objects.requireNonNull(checker, "checker");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager,
                "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 触发时同时验证目标映射和资格快照，二者必须相互匹配。 */
    public Validation validate(UUID tenantId, Instant at) {
        return validate(tenantId, at, false);
    }

    /**
     * 支持可选行锁的同一校验。
     * 触发事务使用加锁版本，防止校验到写入 Outbox 之间具体收件人或资格快照发生变化。
     */
    public Validation validate(UUID tenantId, Instant at, boolean lock) {
        requireTenant(tenantId);
        Instant now = (at == null ? now() : at).truncatedTo(ChronoUnit.MICROS);
        if (countActiveSlaEscalationTargets(tenantId) != 1) {
            return Validation.invalid("SLA_TARGET_MISSING_OR_DUPLICATE");
        }
        com.flowdesk.ticket.model.sla.SlaEscalationTargetRow target = findActiveSlaEscalationTarget(tenantId, lock).orElse(null);
        if (target == null) return Validation.invalid("SLA_TARGET_MISSING_OR_DUPLICATE");
        com.flowdesk.ticket.model.sla.SlaQualificationRow snapshot = findQualificationSnapshot(tenantId, target.targetId(), lock).orElse(null);
        if (snapshot == null) return Validation.invalid("SLA_TARGET_QUALIFICATION_MISSING");
        Instant checkedAt = target.qualificationCheckedAt();
        Instant validUntil = target.qualificationValidUntil();
        boolean targetWindow = validWindow(checkedAt, validUntil, now);
        boolean snapshotWindow = validWindow(snapshot.checkedAt(), snapshot.validUntil(), now);
        boolean valid = "ACTIVE".equals(target.status())
                && "SLA".equals(target.purpose())
                && concrete(target.recipientUserId())
                && target.effectiveAt() != null && !target.effectiveAt().isAfter(now)
                && "ELIGIBLE".equals(target.qualificationState())
                && target.qualificationVersion() != null && target.qualificationVersion() > 0
                && targetWindow
                && snapshot.targetId() != null && snapshot.targetId().equals(target.targetId())
                && concrete(snapshot.recipientUserId())
                && snapshot.recipientUserId().equals(target.recipientUserId())
                && "SLA".equals(snapshot.purpose())
                && snapshot.qualificationVersion() > 0
                && snapshot.qualificationVersion() == target.qualificationVersion()
                && Objects.equals(snapshot.checkedAt(), checkedAt)
                && Objects.equals(snapshot.validUntil(), validUntil)
                && snapshotWindow
                && snapshot.eligible()
                && "ELIGIBLE".equals(snapshot.reasonCode())
                && "ELIGIBLE".equals(target.qualificationReasonCode());
        return valid ? Validation.valid(target.recipientUserId(), target.qualificationVersion())
                : Validation.invalid("SLA_TARGET_QUALIFICATION_INVALID");
    }

    /** 刷新单个目标；所有 IAM 故障均转换为本地 DEGRADED 快照。 */
    public RefreshResult refreshTenant(UUID tenantId) {
        requireTenant(tenantId);
        Instant at = now();
        if (countActiveSlaEscalationTargets(tenantId) != 1) {
            appendRefreshAudit(tenantId, null, "SLA_TARGET_MISSING_OR_DUPLICATE", at);
            return new RefreshResult(tenantId, false, "SLA_TARGET_MISSING_OR_DUPLICATE");
        }
        com.flowdesk.ticket.model.sla.SlaEscalationTargetRow target = findActiveSlaEscalationTarget(tenantId, false).orElse(null);
        if (target == null) {
            appendRefreshAudit(tenantId, null, "SLA_TARGET_MISSING_OR_DUPLICATE", at);
            return new RefreshResult(tenantId, false, "SLA_TARGET_MISSING_OR_DUPLICATE");
        }

        SlaTargetQualificationChecker.Qualification result;
        try {
            result = checker.checkServiceDeskAdmin(tenantId, target.recipientUserId());
            if (!validResult(result, target, at)) {
                return persist(tenantId, target, false, "IAM_RESULT_INVALID", at,
                        usableVersion(target), at.plus(properties.getQualificationMaxAge()), "DEGRADED");
            }
        }
        catch (RuntimeException unavailable) {
            LOG.warn("SLA target qualification unavailable; target remains degraded");
            return persist(tenantId, target, false, "IAM_UNAVAILABLE", at,
                    usableVersion(target), at.plus(properties.getQualificationMaxAge()), "DEGRADED");
        }
        Instant validUntil = result.checkedAt().plus(properties.getQualificationMaxAge())
                .truncatedTo(ChronoUnit.MICROS);
        // IAM 否定结果表示资格降级，不永久改写配置决策。
        // 后续刷新可直接恢复目标，无需迁移映射；两种状态下的触发校验均保守拒绝无资格对象。
        String state = result.eligible() && "ELIGIBLE".equals(result.reasonCode())
                ? "ELIGIBLE" : "DEGRADED";
        return persist(tenantId, target, result.eligible() && "ELIGIBLE".equals(result.reasonCode()),
                safeReason(result.reasonCode()), result.checkedAt(), result.qualificationVersion(), validUntil, state);
    }

    /**
     * 按有界租户批次刷新资格，逐租户隔离处理与失败结果。
     */
    public int refreshAll() {
        int ready = 0;
        UUID cursor = null;
        while (true) {
            List<UUID> tenants = findTenantIdsForSlaMaintenance(cursor,
                    properties.getTenantBatchSize());
            if (tenants.isEmpty()) break;
            for (UUID tenant : tenants) {
                TenantContext context = TenantContext.ofPersistedTask(tenant,
                        ActorRef.service("flowdesk-ticket-sla-qualification"), UUID.randomUUID());
                try (TenantContextScope ignored = TenantContextScope.open(context)) {
                    if (refreshTenant(tenant).eligible()) ready++;
                }
                catch (RuntimeException failure) {
                    // 单个租户配置错误或 IAM/数据库暂时故障不能终止其他租户的有界刷新。
                    LOG.warn("SLA qualification refresh failed for one tenant; continuing");
                }
            }
            cursor = tenants.get(tenants.size() - 1);
        }
        return ready;
    }

    /**
     * 校验远程资格结果后保存本地快照，并记录刷新审计。
     */
    private RefreshResult persist(UUID tenantId, com.flowdesk.ticket.model.sla.SlaEscalationTargetRow target,
                                  boolean eligible, String reason, Instant checkedAt,
                                  long version) {
        Instant at = checkedAt == null ? now() : checkedAt;
        Instant validUntil = at.plus(properties.getQualificationMaxAge()).truncatedTo(ChronoUnit.MICROS);
        return persist(tenantId, target, eligible, reason, at, version, validUntil,
                eligible ? "ELIGIBLE" : "DEGRADED");
    }

    /**
     * 校验远程资格结果后保存本地快照，并记录刷新审计。
     */
    private RefreshResult persist(UUID tenantId, com.flowdesk.ticket.model.sla.SlaEscalationTargetRow target,
                                  boolean eligible, String reason, Instant checkedAt,
                                  long version, Instant validUntil, String state) {
        Instant updatedAt = now();
        if (target == null || version <= 0 || checkedAt == null || validUntil == null) {
            return new RefreshResult(tenantId, false, "QUALIFICATION_RESULT_INCOMPLETE");
        }
        Instant checked = checkedAt.truncatedTo(ChronoUnit.MICROS);
        Instant until = validUntil.truncatedTo(ChronoUnit.MICROS);
        Instant expectedUntil = checked.plus(properties.getQualificationMaxAge())
                .truncatedTo(ChronoUnit.MICROS);
        if (!expectedUntil.equals(until) || !validWindow(checked, until, updatedAt)
                || !validReason(reason) || !Set.of("ELIGIBLE", "DEGRADED").contains(state)
                || (eligible != "ELIGIBLE".equals(reason))) {
            return new RefreshResult(tenantId, false, "QUALIFICATION_WINDOW_INVALID");
        }
        Boolean changed = transactions.execute(tx -> {
            com.flowdesk.ticket.model.sla.SlaEscalationTargetRow locked = findActiveSlaEscalationTarget(tenantId, true).orElse(null);
            if (locked == null || !"ACTIVE".equals(locked.status())
                    || !"SLA".equals(locked.purpose())
                    || !locked.targetId().equals(target.targetId())
                    || !Objects.equals(locked.recipientUserId(), target.recipientUserId())
                    || (locked.qualificationVersion() != null
                    && version < locked.qualificationVersion())) return false;
            String targetState = eligible ? "ELIGIBLE" : state;
            int updated = updateSlaTargetQualification(tenantId, locked.targetId(),
                    locked.lockVersion(), version, checked, until, eligible,
                    safeReason(reason), targetState, updatedAt);
            if (updated != 1) return false;
            upsertQualificationSnapshot(tenantId, locked.targetId(),
                    locked.recipientUserId(), version, checked, until, eligible,
                    safeReason(reason), updatedAt);
            com.flowdesk.ticket.model.sla.SlaQualificationRow snapshot = findQualificationSnapshot(tenantId, locked.targetId(), true)
                    .orElseThrow(() -> new IllegalStateException(
                            "SLA qualification snapshot write lost"));
            if (!sameSnapshot(snapshot, locked, version, checked, until, eligible,
                    safeReason(reason))) {
                throw new IllegalStateException("SLA qualification snapshot content conflict");
            }
            appendRefreshAuditInTransaction(tenantId, locked.targetId(),
                    eligible ? "SLA_TARGET_QUALIFICATION_REFRESHED"
                            : "SLA_TARGET_QUALIFICATION_DEGRADED",
                    safeReason(reason), updatedAt);
            return true;
        });
        return new RefreshResult(tenantId, Boolean.TRUE.equals(changed),
                Boolean.TRUE.equals(changed) ? (eligible ? "ELIGIBLE" : state) : "TARGET_UPDATE_CONFLICT");
    }

    /**
     * 校验下游资格结果具备业务判断所需的标识、版本与时间信息。
     */
    private boolean validResult(SlaTargetQualificationChecker.Qualification result,
                                com.flowdesk.ticket.model.sla.SlaEscalationTargetRow target, Instant now) {
        if (result == null || !concrete(target.recipientUserId())
                || !target.recipientUserId().equals(result.subjectUserId())
                || result.qualificationVersion() <= 0 || result.checkedAt() == null
                || !result.checkedAt().equals(result.checkedAt().truncatedTo(ChronoUnit.MICROS))
                || result.reasonCode() == null
                || !result.reasonCode().matches("[A-Z][A-Z0-9_.:-]{0,95}")
                || result.checkedAt().isBefore(now.minus(properties.getQualificationMaxAge()))
                || result.checkedAt().isAfter(now.plus(properties.getQualificationClockSkew()))
                || (result.eligible() != "ELIGIBLE".equals(result.reasonCode()))) {
            return false;
        }
        // 同一用户和角色的 IAM 资格版本单调递增；旧于本地快照的响应不能重新放行已更新目标。
        return target.qualificationVersion() == null
                || result.qualificationVersion() >= target.qualificationVersion();
    }

    /**
     * 判断资格规则版本是否可用于当前业务处理。
     */
    private static long usableVersion(com.flowdesk.ticket.model.sla.SlaEscalationTargetRow target) {
        return target.qualificationVersion() == null || target.qualificationVersion() < 1
                ? 1L : target.qualificationVersion();
    }

    /**
     * 记录资格刷新结果，保留变化或无法确认资格的原因。
     */
    private void appendRefreshAudit(UUID tenantId, UUID targetId, String action, Instant now) {
        try {
            transactions.execute(tx -> {
                appendRefreshAuditInTransaction(tenantId, targetId, action, action, now);
                return null;
            });
        }
        catch (RuntimeException failure) {
            // 目标缺失或重复时不能凭空选择收件人。
            // 即使审计暂时无法保存，刷新结果仍保守拒绝，后续有界轮询再重试。
            LOG.warn("SLA qualification audit append failed");
        }
    }

    /**
     * 在资格快照保存的同一事务中追加刷新审计。
     */
    private void appendRefreshAuditInTransaction(UUID tenantId, UUID targetId,
                                                  String action, String reason, Instant now) {
        UUID auditId = uuidFromKey(IdempotencyCodec.derivedKey(
                "FD-SLA-QUALIFICATION-AUDIT-V1", tenantId,
                targetId == null ? new UUID(0L, 0L) : targetId,
                action, safeReason(reason)));
        UUID correlationId = TenantContextHolder.current().correlationId();
        if (ticketAuditMapper.findAudit(tenantId, auditId).isEmpty()) {
            ticketAuditMapper.insertAudit(tenantId, auditId, action, "SLA_ESCALATION_TARGET",
                    targetId, "SUCCEEDED", "SERVICE", null,
                    "SLA qualification refresh result: " + safeReason(reason), null,
                    correlationId, null, now, now);
        }
    }

    /**
     * 校验资格时间窗口，拒绝尚未生效、过期或跨度不合法的结果。
     */
    private boolean validWindow(Instant checkedAt, Instant validUntil, Instant now) {
        if (checkedAt == null || validUntil == null || now == null
                || !checkedAt.equals(checkedAt.truncatedTo(ChronoUnit.MICROS))
                || !validUntil.equals(validUntil.truncatedTo(ChronoUnit.MICROS))) {
            return false;
        }
        Instant expected = checkedAt.plus(properties.getQualificationMaxAge())
                .truncatedTo(ChronoUnit.MICROS);
        return expected.equals(validUntil)
                && !checkedAt.isBefore(now.minus(properties.getQualificationMaxAge()))
                && !checkedAt.isAfter(now.plus(properties.getQualificationClockSkew()))
                && now.isBefore(validUntil);
    }

    /**
     * 判断标识或资格字段是否为具体值，排除空值与占位身份。
     */
    private static boolean concrete(UUID value) {
        return value != null && !(value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L);
    }

    /**
     * 比较资格快照业务内容，区分重复结果与实际变化。
     */
    private static boolean sameSnapshot(com.flowdesk.ticket.model.sla.SlaQualificationRow snapshot,
                                        com.flowdesk.ticket.model.sla.SlaEscalationTargetRow target,
                                        long version, Instant checkedAt, Instant validUntil,
                                        boolean eligible, String reason) {
        return target.targetId().equals(snapshot.targetId())
                && target.recipientUserId().equals(snapshot.recipientUserId())
                && "SLA".equals(snapshot.purpose())
                && snapshot.qualificationVersion() == version
                && Objects.equals(snapshot.checkedAt(), checkedAt)
                && Objects.equals(snapshot.validUntil(), validUntil)
                && snapshot.eligible() == eligible
                && Objects.equals(snapshot.reasonCode(), reason);
    }

    /**
     * 校验传入租户与当前上下文一致，阻止跨租户持久化操作。
     */
    private static void requireTenant(UUID tenantId) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId is required");
        TenantContextHolder.current().requireTenant(tenantId);
    }

    /**
     * 将稳定幂等键转为 UUID，保持业务事实与关联记录身份一致。
     */
    private static UUID uuidFromKey(byte[] key) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(java.util.Arrays.copyOf(key, 16));
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /**
     * 清理原因文本并限制长度，供审计与恢复信息保存。
     */
    private static String safeReason(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_.:-]{0,95}")) return "IAM_RESULT_INVALID";
        return value;
    }

    /**
     * 检查操作原因是否满足格式及长度约束。
     */
    private static boolean validReason(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_.:-]{0,95}");
    }

    /**
     * 取得当前时间并规范为业务使用的时间精度。
     */
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }





    /**
     * 按租户游标读取维护范围，限制每轮资格刷新数量。
     */
    private List<UUID> findTenantIdsForSlaMaintenance(int limit) {
        return findTenantIdsForSlaMaintenance(null, limit);
    }

    /**
     * 按租户游标读取维护范围，限制每轮资格刷新数量。
     */
    private List<UUID> findTenantIdsForSlaMaintenance(UUID afterTenantId, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("tenant limit must be between 1 and 1000");
        return mapper.findTenantIdsForSlaMaintenanceSelect(afterTenantId, limit);
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
     * 统计租户启用升级目标数，供配置一致性检查。
     */
    private int countActiveSlaEscalationTargets(UUID tenantId) {
        requireTenant(tenantId);
        Integer count = mapper.countActiveSlaEscalationTargetsSelect(Uuids.toBytes(tenantId));
        return count == null ? 0 : count;
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

    /**
     * 读取资格快照、规则版本和有效期，供通知前本地核验。
     */
    private Optional<SlaQualificationRow> findQualificationSnapshot(UUID tenantId, UUID targetId) {
        return findQualificationSnapshot(tenantId, targetId, false);
    }

    /**
     * 读取资格快照、规则版本和有效期，供通知前本地核验。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 传入租户必须与当前上下文一致。
     */
    private Optional<SlaQualificationRow> findQualificationSnapshot(UUID tenantId, UUID targetId,
                                                                    boolean lock) {
        requireTenant(tenantId);
        if (lock) requireTransaction();
        return mapper.findQualificationSnapshotSelect(Uuids.toBytes(tenantId), Uuids.toBytes(targetId), lock)
                .stream().findFirst();
    }

    /**
     * 更新目标本地资格状态，使新触发采用刷新结果。
     * 调用必须处于数据库事务中，以保持当前步骤与关联事实原子提交。
     * 传入租户必须与当前上下文一致。
     */
    private int updateSlaTargetQualification(UUID tenantId, UUID targetId, long expectedVersion,
                                            long qualificationVersion, Instant checkedAt,
                                            Instant validUntil, boolean eligible, String reason,
                                            String state, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        return mapper.updateSlaTargetQualificationUpdate(qualificationVersion, Timestamp.from(checkedAt), Timestamp.from(validUntil), state, reason, Timestamp.from(now), Uuids.toBytes(tenantId), Uuids.toBytes(targetId), expectedVersion);
    }
}
