package com.flowdesk.iam.service.impl;

import com.flowdesk.iam.model.*;
import com.flowdesk.iam.service.*;

import com.flowdesk.common.security.JwtPrincipal;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.common.web.JwtPrincipalHolder;
import com.flowdesk.iam.dto.IamDtos;
import com.flowdesk.iam.mapper.EligibilityMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 只读的同步资格预检（API-016）；调用方应在自己的业务写事务开始前完成此检查。
 */
@Service
public class EligibilityServiceImpl implements EligibilityService {

    private final EligibilityMapper repository;
    private final Clock clock;

    /** 使用 UTC 系统时钟组装资格预检服务，统一资格检查结果中的 checkedAt。 */
    public EligibilityServiceImpl(EligibilityMapper repository) {
        this(repository, Clock.systemUTC());
    }

    /** 注入资格事实查询及可替换时钟，便于以固定时间验证资格判定。 */
    @Autowired
    public EligibilityServiceImpl(EligibilityMapper repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 校验调用服务、目标角色和业务用途的白名单组合，再读取当前租户的资格事实。
     * 依次判断用户启用状态、角色授予状态及角色启用状态，返回首个不满足条件的原因与事实版本。
     */
    public IamDtos.EligibilityCheckResult check(IamDtos.EligibilityCheckRequest request) {
        TenantContext context = TenantContextHolder.current();
        JwtPrincipal principal = JwtPrincipalHolder.current();
        // 将调用服务、所需角色和业务用途作为整体白名单校验，避免服务身份被用于任意资格探测。
        validateTriple(principal.serviceId(), request.requiredRoleCode().name(), request.purpose().name());

        UUID subjectUserId = request.subjectUserId();
        Instant checkedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        EligibilityFacts facts = repository.findEligibilityFacts(
                context.tenantId(), subjectUserId, request.requiredRoleCode().name()).orElse(null);
        if (facts == null) {
            return result(subjectUserId, false, "NOT_FOUND", 1L, checkedAt);
        }
        // 资格同时依赖用户、授权关系和角色状态；保留事实版本供调用方识别本次检查。
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

    /** 将资格结论、原因和本次事实版本封装为统一响应，保留一次检查共用的时间戳。 */
    private static IamDtos.EligibilityCheckResult result(UUID subjectUserId, boolean eligible,
                                                          String reasonCode, long version,
                                                          Instant checkedAt) {
        return new IamDtos.EligibilityCheckResult(subjectUserId, eligible, reasonCode,
                version, checkedAt);
    }

    /**
     * 仅允许工单服务检查接单人或 SLA 升级目标、流程服务检查审批人。
     * 调用服务与角色、用途任一不匹配都拒绝访问，防止服务 JWT 被用于任意人员资格探测。
     */
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
