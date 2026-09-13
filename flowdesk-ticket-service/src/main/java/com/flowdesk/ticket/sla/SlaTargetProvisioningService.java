package com.flowdesk.ticket.sla;

import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.ticket.iam.SlaTargetQualificationChecker;
import com.flowdesk.ticket.repository.TicketRepository;
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
 * Controlled lifecycle for the Ticket-owned concrete SLA escalation mapping.
 *
 * <p>This is intentionally a service-side provisioning port rather than a
 * tenant-facing controller. IAM is consulted before opening the Ticket write
 * transaction. The transaction then locks the current mapping, changes the
 * mapping and snapshot together, and appends one deterministic audit fact.</p>
 */
@Service
public final class SlaTargetProvisioningService {
    private static final String PURPOSE = "SLA";
    private static final String ACTOR_TYPE = "SERVICE";
    private static final String UNKNOWN_REASON = "IAM_RESULT_INVALID";

    private final TicketRepository repository;
    private final SlaTargetQualificationChecker qualificationChecker;
    private final TicketSlaProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public SlaTargetProvisioningService(TicketRepository repository,
                                        SlaTargetQualificationChecker qualificationChecker,
                                        TicketSlaProperties properties,
                                        PlatformTransactionManager transactionManager,
                                        Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.qualificationChecker = Objects.requireNonNull(qualificationChecker,
                "qualificationChecker");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager,
                "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Creates the first active target for a tenant. */
    public ProvisionResult create(UUID tenantId, UUID targetId, UUID recipientUserId,
                                  Instant effectiveAt) {
        return create(tenantId, targetId, recipientUserId, effectiveAt, null);
    }

    /** Creates a target with an explicit provisioning actor for audit metadata. */
    public ProvisionResult create(UUID tenantId, UUID targetId, UUID recipientUserId,
                                  Instant effectiveAt, UUID actorId) {
        requireTenant(tenantId);
        Inputs inputs = inputs(tenantId, targetId, recipientUserId, effectiveAt);
        QualificationSnapshot snapshot = qualify(tenantId, recipientUserId);
        // Provisioning is the only path that can promote a concrete UserRef
        // into the ACTIVE SLA mapping.  A negative IAM answer is useful for
        // refreshing an already configured mapping into DEGRADED, but it must
        // never replace a known-good target or create a newly activatable one.
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

    /** Replaces the currently active target, retaining the old row as history. */
    public ProvisionResult replace(UUID tenantId, UUID newTargetId, UUID recipientUserId,
                                   Instant effectiveAt) {
        return replace(tenantId, null, newTargetId, recipientUserId, effectiveAt, null);
    }

    /**
     * Replaces a specific expected old target. Supplying the old identity makes
     * an operator retry fail closed if another replacement won the race.
     */
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

    /** Convenience replacement overload for controlled callers without actor id. */
    public ProvisionResult replace(UUID tenantId, UUID oldTargetId, UUID newTargetId,
                                   UUID recipientUserId, Instant effectiveAt) {
        return replace(tenantId, oldTargetId, newTargetId, recipientUserId, effectiveAt, null);
    }

    /** Deactivates a target without deleting its historical mapping. */
    public ProvisionResult deactivate(UUID tenantId, UUID targetId) {
        return deactivate(tenantId, targetId, null);
    }

    public ProvisionResult deactivate(UUID tenantId, UUID targetId, UUID actorId) {
        requireTenant(tenantId);
        if (targetId == null) {
            return new ProvisionResult(tenantId, null, false, false, "TARGET_ID_REQUIRED");
        }
        return transactions.execute(tx -> {
            TicketRepository.SlaEscalationTargetRow target = repository
                    .findSlaEscalationTarget(tenantId, targetId, true).orElse(null);
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
            int changed = repository.deactivateSlaEscalationTarget(tenantId, targetId,
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

    private ProvisionResult createInTransaction(Inputs inputs,
                                               QualificationSnapshot snapshot,
                                               UUID actorId) {
        Optional<TicketRepository.SlaEscalationTargetRow> active = repository
                .findActiveSlaEscalationTarget(inputs.tenantId(), true);
        if (active.isPresent()) {
            TicketRepository.SlaEscalationTargetRow row = active.get();
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
        repository.insertSlaEscalationTarget(inputs.tenantId(), inputs.targetId(),
                inputs.recipientUserId(), PURPOSE, "ACTIVE", inputs.effectiveAt(),
                snapshot.version(), snapshot.checkedAt(), snapshot.validUntil(), snapshot.state(),
                snapshot.reasonCode(), ACTOR_TYPE, actorId, at);
        repository.upsertQualificationSnapshot(inputs.tenantId(), inputs.targetId(),
                inputs.recipientUserId(), snapshot.version(), snapshot.checkedAt(),
                snapshot.validUntil(), snapshot.eligible(), snapshot.reasonCode(), at);
        appendAudit(inputs.tenantId(), inputs.targetId(), "SLA_TARGET_PROVISIONED", "SUCCEEDED",
                snapshot.reasonCode(), actorId, at);
        return new ProvisionResult(inputs.tenantId(), inputs.targetId(), true, false,
                snapshot.eligible() ? "PROVISIONED" : "PROVISIONED_DEGRADED");
    }

    private ProvisionResult replaceInTransaction(UUID tenantId, UUID expectedOldTargetId,
                                                 Inputs inputs,
                                                 QualificationSnapshot snapshot,
                                                 UUID actorId) {
        TicketRepository.SlaEscalationTargetRow active = repository
                .findActiveSlaEscalationTarget(tenantId, true).orElse(null);
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

        // The active row is locked above. Deactivate it before inserting the
        // replacement so the generated active guard remains satisfiable.
        Instant at = now();
        int deactivated = repository.deactivateSlaEscalationTarget(tenantId,
                active.targetId(), active.lockVersion(), ACTOR_TYPE, actorId, at);
        if (deactivated != 1) {
            throw new IllegalStateException("SLA target replacement lost old-row lock");
        }
        repository.insertSlaEscalationTarget(inputs.tenantId(), inputs.targetId(),
                inputs.recipientUserId(), PURPOSE, "ACTIVE", inputs.effectiveAt(),
                snapshot.version(), snapshot.checkedAt(), snapshot.validUntil(), snapshot.state(),
                snapshot.reasonCode(), ACTOR_TYPE, actorId, at);
        repository.upsertQualificationSnapshot(inputs.tenantId(), inputs.targetId(),
                inputs.recipientUserId(), snapshot.version(), snapshot.checkedAt(),
                snapshot.validUntil(), snapshot.eligible(), snapshot.reasonCode(), at);
        appendAudit(tenantId, active.targetId(), "SLA_TARGET_REPLACED_FROM", "SUCCEEDED",
                inputs.targetId().toString(), actorId, at);
        appendAudit(tenantId, inputs.targetId(), "SLA_TARGET_REPLACED", "SUCCEEDED",
                snapshot.reasonCode(), actorId, at);
        return new ProvisionResult(tenantId, inputs.targetId(), true, false,
                snapshot.eligible() ? "REPLACED" : "REPLACED_DEGRADED");
    }

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

    private void appendAudit(UUID tenantId, UUID targetId, String action, String outcome,
                             String reason, UUID actorId, Instant at) {
        UUID auditId = IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(
                "FD-SLA-TARGET-PROVISIONING-AUDIT-V1", tenantId, targetId, action,
                reason == null ? "" : reason));
        Optional<TicketRepository.AuditRow> existing = repository.findAudit(tenantId, auditId);
        if (existing.isPresent()) {
            TicketRepository.AuditRow row = existing.get();
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
            repository.insertAudit(tenantId, auditId, action, "SLA_ESCALATION_TARGET", targetId,
                    outcome, ACTOR_TYPE, actorId, reason, null, correlationId, null, at, at);
        }
        catch (DuplicateKeyException duplicate) {
            TicketRepository.AuditRow row = repository.findAudit(tenantId, auditId)
                    .orElseThrow(() -> duplicate);
            if (!Objects.equals(row.actionCode(), action)
                    || !Objects.equals(row.targetId(), targetId)
                    || !Objects.equals(row.reason(), reason)) {
                throw new IllegalStateException("SLA target provisioning audit conflict", duplicate);
            }
        }
    }

    private Inputs inputs(UUID tenantId, UUID targetId, UUID recipientUserId,
                          Instant effectiveAt) {
        if (tenantId == null || targetId == null || recipientUserId == null || effectiveAt == null
                || isZero(targetId) || isZero(recipientUserId)) {
            throw new IllegalArgumentException("SLA target provisioning values are incomplete");
        }
        return new Inputs(tenantId, targetId, recipientUserId,
                effectiveAt.truncatedTo(ChronoUnit.MICROS));
    }

    private static boolean validReason(String reason) {
        return reason != null && reason.matches("[A-Z][A-Z0-9_.:-]{0,95}");
    }

    private static String safeReason(String reason) {
        return validReason(reason) ? reason : UNKNOWN_REASON;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static void requireTenant(UUID tenantId) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId is required");
        TenantContextHolder.current().requireTenant(tenantId);
    }

    private static boolean isZero(UUID value) {
        return value != null && value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L;
    }

    private record Inputs(UUID tenantId, UUID targetId, UUID recipientUserId, Instant effectiveAt) { }

    private record QualificationSnapshot(boolean writable, boolean eligible, String state,
                                        String reasonCode, long version, Instant checkedAt,
                                        Instant validUntil, String code) {
        private static QualificationSnapshot unwritable(String code) {
            return new QualificationSnapshot(false, false, "DEGRADED", safeReason(code),
                    1L, Instant.EPOCH, Instant.EPOCH, code);
        }
    }

    public record ProvisionResult(UUID tenantId, UUID targetId, boolean changed,
                                  boolean replayed, String code) { }
}
