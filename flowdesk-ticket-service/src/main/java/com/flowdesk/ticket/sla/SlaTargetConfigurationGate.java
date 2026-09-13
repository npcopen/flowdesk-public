package com.flowdesk.ticket.sla;

import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.ticket.repository.TicketRepository;

import java.time.Instant;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Fail-closed activation gate for the Ticket-owned ServiceDeskAdmin mapping.
 * It intentionally exposes only a concrete recipient and never a role name.
 */
public final class SlaTargetConfigurationGate {
    private final TicketRepository repository;
    private final SlaTargetQualificationService qualification;
    private final Clock clock;

    public SlaTargetConfigurationGate(TicketRepository repository,
                                      SlaTargetQualificationService qualification,
                                      Clock clock) {
        this.repository = repository;
        this.qualification = qualification;
        this.clock = clock;
    }

    public GateResult evaluate(UUID tenantId, Instant at, boolean lock) {
        if (tenantId == null) return GateResult.closed("SLA_TARGET_TENANT_REQUIRED");
        TenantContextHolder.current().requireTenant(tenantId);
        Instant now = (at == null ? clock.instant() : at).truncatedTo(ChronoUnit.MICROS);
        if (repository.countActiveSlaEscalationTargets(tenantId) != 1) {
            return GateResult.closed("SLA_TARGET_MISSING_OR_DUPLICATE");
        }
        TicketRepository.SlaEscalationTargetRow target = repository
                .findActiveSlaEscalationTarget(tenantId, lock).orElse(null);
        if (target == null || target.effectiveAt() == null || target.effectiveAt().isAfter(now)) {
            return GateResult.closed("SLA_TARGET_NOT_EFFECTIVE");
        }
        SlaTargetQualificationService.Validation result = qualification.validate(tenantId, now, lock);
        return result.eligible()
                ? GateResult.open(result.recipientUserId(), result.qualificationVersion())
                : GateResult.closed(result.reasonCode());
    }

    public record GateResult(boolean open, UUID recipientUserId,
                             Long qualificationVersion, String reasonCode) {
        public static GateResult open(UUID recipient, Long version) {
            return new GateResult(true, recipient, version, "ELIGIBLE");
        }
        public static GateResult closed(String reason) {
            return new GateResult(false, null, null,
                    reason == null ? "SLA_TARGET_GATE_CLOSED" : reason);
        }
    }
}
