package com.flowdesk.iam.service;

import com.flowdesk.common.security.JwtPrincipal;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.common.web.JwtPrincipalHolder;
import com.flowdesk.iam.api.IamDtos;
import com.flowdesk.iam.repository.IamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * API-016 synchronous eligibility pre-check. Read-only and called before any
 * caller-side business write transaction.
 */
@Service
public class EligibilityService {

    private final IamRepository repository;
    private final Clock clock;

    public EligibilityService(IamRepository repository) {
        this(repository, Clock.systemUTC());
    }

    @Autowired
    public EligibilityService(IamRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public IamDtos.EligibilityCheckResult check(IamDtos.EligibilityCheckRequest request) {
        TenantContext context = TenantContextHolder.current();
        JwtPrincipal principal = JwtPrincipalHolder.current();
        validateTriple(principal.serviceId(), request.requiredRoleCode().name(), request.purpose().name());

        UUID subjectUserId = request.subjectUserId();
        Instant checkedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        IamRepository.EligibilityFacts facts = repository.findEligibilityFacts(
                context.tenantId(), subjectUserId, request.requiredRoleCode().name()).orElse(null);
        if (facts == null) {
            return result(subjectUserId, false, "NOT_FOUND", 1L, checkedAt);
        }
        long qualificationVersion = facts.qualificationVersion();
        if (!"ACTIVE".equals(facts.userStatus())) {
            return result(subjectUserId, false, "USER_INACTIVE", qualificationVersion, checkedAt);
        }
        if (!"GRANTED".equals(facts.assignmentState())) {
            return result(subjectUserId, false, "ROLE_NOT_GRANTED", qualificationVersion, checkedAt);
        }
        if (!"ACTIVE".equals(facts.roleStatus())) {
            return result(subjectUserId, false, "ROLE_INACTIVE", qualificationVersion, checkedAt);
        }
        return result(subjectUserId, true, "ELIGIBLE", qualificationVersion, checkedAt);
    }

    private static IamDtos.EligibilityCheckResult result(UUID subjectUserId, boolean eligible,
                                                          String reasonCode, long version,
                                                          Instant checkedAt) {
        return new IamDtos.EligibilityCheckResult(subjectUserId, eligible, reasonCode,
                version, checkedAt);
    }

    private static void validateTriple(String serviceId, String roleCode, String purpose) {
        boolean allowed = switch (serviceId) {
            case "flowdesk-ticket-service" ->
                    ("AGENT".equals(roleCode) && "TICKET_ASSIGNEE".equals(purpose))
                            || ("SERVICE_DESK_ADMIN".equals(roleCode)
                            && "SLA_ESCALATION_TARGET".equals(purpose));
            case "flowdesk-workflow-service" ->
                    "APPROVER".equals(roleCode) && "WORKFLOW_APPROVER".equals(purpose);
            default -> false;
        };
        if (!allowed) {
            throw ApiException.forbidden("ELIGIBILITY_PURPOSE_FORBIDDEN",
                    "服务 caller 与资格 purpose 白名单不匹配。");
        }
    }
}
