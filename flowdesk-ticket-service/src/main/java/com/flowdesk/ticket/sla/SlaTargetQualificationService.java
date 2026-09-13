package com.flowdesk.ticket.sla;

import com.flowdesk.ticket.iam.SlaTargetQualificationChecker;
import com.flowdesk.ticket.repository.TicketRepository;
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
 * Owns the Ticket-side qualification snapshot for the concrete SLA target.
 * IAM is consulted outside the Ticket write transaction; only the immutable
 * result and a short validity window are stored locally.
 */
@Service
public final class SlaTargetQualificationService {
    private static final Logger LOG = LoggerFactory.getLogger(SlaTargetQualificationService.class);

    private final TicketRepository repository;
    private final SlaTargetQualificationChecker checker;
    private final TicketSlaProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public SlaTargetQualificationService(TicketRepository repository,
                                          SlaTargetQualificationChecker checker,
                                          TicketSlaProperties properties,
                                          PlatformTransactionManager transactionManager,
                                          Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.checker = Objects.requireNonNull(checker, "checker");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager,
                "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Strict trigger-time validation using both target and snapshot facts. */
    public Validation validate(UUID tenantId, Instant at) {
        return validate(tenantId, at, false);
    }

    /**
     * Same validation with an optional row lock.  Trigger transactions use the
     * lock variant so the concrete recipient and qualification snapshot cannot
     * change between validation and Outbox insertion.
     */
    public Validation validate(UUID tenantId, Instant at, boolean lock) {
        requireTenant(tenantId);
        Instant now = (at == null ? now() : at).truncatedTo(ChronoUnit.MICROS);
        if (repository.countActiveSlaEscalationTargets(tenantId) != 1) {
            return Validation.invalid("SLA_TARGET_MISSING_OR_DUPLICATE");
        }
        TicketRepository.SlaEscalationTargetRow target = repository
                .findActiveSlaEscalationTarget(tenantId, lock).orElse(null);
        if (target == null) return Validation.invalid("SLA_TARGET_MISSING_OR_DUPLICATE");
        TicketRepository.SlaQualificationRow snapshot = repository
                .findQualificationSnapshot(tenantId, target.targetId(), lock).orElse(null);
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

    /** Refreshes one target; all IAM failures become a local DEGRADED snapshot. */
    public RefreshResult refreshTenant(UUID tenantId) {
        requireTenant(tenantId);
        Instant at = now();
        if (repository.countActiveSlaEscalationTargets(tenantId) != 1) {
            appendRefreshAudit(tenantId, null, "SLA_TARGET_MISSING_OR_DUPLICATE", at);
            return new RefreshResult(tenantId, false, "SLA_TARGET_MISSING_OR_DUPLICATE");
        }
        TicketRepository.SlaEscalationTargetRow target = repository
                .findActiveSlaEscalationTarget(tenantId, false).orElse(null);
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
        // Negative IAM answers are deliberately degraded rather than treated as
        // a durable configuration decision.  A later refresh can recover the
        // target without a mapping migration, while trigger-time validation
        // remains fail-closed in either state.
        String state = result.eligible() && "ELIGIBLE".equals(result.reasonCode())
                ? "ELIGIBLE" : "DEGRADED";
        return persist(tenantId, target, result.eligible() && "ELIGIBLE".equals(result.reasonCode()),
                safeReason(result.reasonCode()), result.checkedAt(), result.qualificationVersion(), validUntil, state);
    }

    public int refreshAll() {
        int ready = 0;
        UUID cursor = null;
        while (true) {
            List<UUID> tenants = repository.findTenantIdsForSlaMaintenance(cursor,
                    properties.getTenantBatchSize());
            if (tenants.isEmpty()) break;
            for (UUID tenant : tenants) {
                TenantContext context = TenantContext.ofPersistedTask(tenant,
                        ActorRef.service("flowdesk-ticket-sla-qualification"), UUID.randomUUID());
                try (TenantContextScope ignored = TenantContextScope.open(context)) {
                    if (refreshTenant(tenant).eligible()) ready++;
                }
                catch (RuntimeException failure) {
                    // A malformed tenant or a transient IAM/DB failure must
                    // not terminate the bounded pass for every other tenant.
                    LOG.warn("SLA qualification refresh failed for one tenant; continuing");
                }
            }
            cursor = tenants.get(tenants.size() - 1);
        }
        return ready;
    }

    private RefreshResult persist(UUID tenantId, TicketRepository.SlaEscalationTargetRow target,
                                  boolean eligible, String reason, Instant checkedAt,
                                  long version) {
        Instant at = checkedAt == null ? now() : checkedAt;
        Instant validUntil = at.plus(properties.getQualificationMaxAge()).truncatedTo(ChronoUnit.MICROS);
        return persist(tenantId, target, eligible, reason, at, version, validUntil,
                eligible ? "ELIGIBLE" : "DEGRADED");
    }

    private RefreshResult persist(UUID tenantId, TicketRepository.SlaEscalationTargetRow target,
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
            TicketRepository.SlaEscalationTargetRow locked = repository
                    .findActiveSlaEscalationTarget(tenantId, true).orElse(null);
            if (locked == null || !"ACTIVE".equals(locked.status())
                    || !"SLA".equals(locked.purpose())
                    || !locked.targetId().equals(target.targetId())
                    || !Objects.equals(locked.recipientUserId(), target.recipientUserId())
                    || (locked.qualificationVersion() != null
                    && version < locked.qualificationVersion())) return false;
            String targetState = eligible ? "ELIGIBLE" : state;
            int updated = repository.updateSlaTargetQualification(tenantId, locked.targetId(),
                    locked.lockVersion(), version, checked, until, eligible,
                    safeReason(reason), targetState, updatedAt);
            if (updated != 1) return false;
            repository.upsertQualificationSnapshot(tenantId, locked.targetId(),
                    locked.recipientUserId(), version, checked, until, eligible,
                    safeReason(reason), updatedAt);
            TicketRepository.SlaQualificationRow snapshot = repository
                    .findQualificationSnapshot(tenantId, locked.targetId(), true)
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

    private boolean validResult(SlaTargetQualificationChecker.Qualification result,
                                TicketRepository.SlaEscalationTargetRow target, Instant now) {
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
        // IAM qualification versions are monotonic for a subject/role.  A
        // response older than the local snapshot is stale and cannot reopen a
        // target that has already advanced.
        return target.qualificationVersion() == null
                || result.qualificationVersion() >= target.qualificationVersion();
    }

    private static long usableVersion(TicketRepository.SlaEscalationTargetRow target) {
        return target.qualificationVersion() == null || target.qualificationVersion() < 1
                ? 1L : target.qualificationVersion();
    }

    private void appendRefreshAudit(UUID tenantId, UUID targetId, String action, Instant now) {
        try {
            transactions.execute(tx -> {
                appendRefreshAuditInTransaction(tenantId, targetId, action, action, now);
                return null;
            });
        }
        catch (RuntimeException failure) {
            // A missing/duplicate target must never become a reason to invent a
            // recipient.  Keep the refresh result fail-closed if audit storage
            // is temporarily unavailable; the next bounded pass retries it.
            LOG.warn("SLA qualification audit append failed");
        }
    }

    private void appendRefreshAuditInTransaction(UUID tenantId, UUID targetId,
                                                  String action, String reason, Instant now) {
        UUID auditId = uuidFromKey(IdempotencyCodec.derivedKey(
                "FD-SLA-QUALIFICATION-AUDIT-V1", tenantId,
                targetId == null ? new UUID(0L, 0L) : targetId,
                action, safeReason(reason)));
        UUID correlationId = TenantContextHolder.current().correlationId();
        if (repository.findAudit(tenantId, auditId).isEmpty()) {
            repository.insertAudit(tenantId, auditId, action, "SLA_ESCALATION_TARGET",
                    targetId, "SUCCEEDED", "SERVICE", null,
                    "SLA qualification refresh result: " + safeReason(reason), null,
                    correlationId, null, now, now);
        }
    }

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

    private static boolean concrete(UUID value) {
        return value != null && !(value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L);
    }

    private static boolean sameSnapshot(TicketRepository.SlaQualificationRow snapshot,
                                        TicketRepository.SlaEscalationTargetRow target,
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

    private static void requireTenant(UUID tenantId) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId is required");
        TenantContextHolder.current().requireTenant(tenantId);
    }

    private static UUID uuidFromKey(byte[] key) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(java.util.Arrays.copyOf(key, 16));
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static String safeReason(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_.:-]{0,95}")) return "IAM_RESULT_INVALID";
        return value;
    }

    private static boolean validReason(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_.:-]{0,95}");
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    public record Validation(boolean eligible, UUID recipientUserId,
                             Long qualificationVersion, String reasonCode) {
        public static Validation valid(UUID recipient, long version) {
            return new Validation(true, recipient, version, "ELIGIBLE");
        }
        public static Validation invalid(String reason) {
            return new Validation(false, null, null, reason);
        }
    }

    public record RefreshResult(UUID tenantId, boolean eligible, String code) { }
}
