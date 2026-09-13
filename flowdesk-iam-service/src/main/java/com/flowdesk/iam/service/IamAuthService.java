package com.flowdesk.iam.service;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.RoleCode;
import com.flowdesk.common.security.IssuedJwt;
import com.flowdesk.common.security.JwtIssuer;
import com.flowdesk.common.security.JwtPrincipal;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.common.web.JwtPrincipalHolder;
import com.flowdesk.iam.api.IamDtos;
import com.flowdesk.iam.repository.IamRepository;
import com.flowdesk.iam.security.LoginSecurityProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * API-001 login and API-002 current-user use cases.
 */
@Service
public class IamAuthService {

    private final IamRepository repository;
    private final PasswordHashService passwordHashService;
    private final JwtIssuer jwtIssuer;
    private final LoginSecurityProperties loginSecurity;
    private final Clock clock;

    public IamAuthService(IamRepository repository,
                          PasswordHashService passwordHashService,
                          JwtIssuer jwtIssuer,
                          LoginSecurityProperties loginSecurity) {
        this(repository, passwordHashService, jwtIssuer, loginSecurity, Clock.systemUTC());
    }

    @Autowired
    public IamAuthService(IamRepository repository,
                          PasswordHashService passwordHashService,
                          JwtIssuer jwtIssuer,
                          LoginSecurityProperties loginSecurity,
                          Clock clock) {
        this.repository = repository;
        this.passwordHashService = passwordHashService;
        this.jwtIssuer = jwtIssuer;
        this.loginSecurity = loginSecurity;
        this.clock = clock;
    }

    @Transactional
    public IamDtos.AuthSessionResponse login(IamDtos.LoginRequest request, UUID correlationId) {
        List<UUID> tenantIds = repository.findTenantIdsByCode(request.tenantCode());
        IamRepository.CredentialRow matched = null;
        int matchCount = 0;
        List<IamRepository.CredentialRow> passwordMismatches = new ArrayList<>();
        int passwordComparisons = 0;
        for (UUID tenantId : tenantIds) {
            var credential = repository.findCredential(tenantId, request.username()).orElse(null);
            if (credential != null) {
                passwordComparisons++;
                if (passwordHashService.matches(request.password(), credential.passwordHash())) {
                    matchCount++;
                    matched = credential;
                } else {
                    passwordMismatches.add(credential);
                }
            }
        }
        if (passwordComparisons == 0) {
            passwordHashService.consumeTimingDummy(request.password());
        }
        if (matchCount != 1 || matched == null) {
            // Zero and ambiguous credential matches share the exact same observable result.
            // Record the failure on an unambiguous single account only; with zero or
            // multiple candidates there is no safe account to attribute the attempt to,
            // so the failed counter is not touched (avoids a per-tenant oracle).
            if (passwordMismatches.size() == 1) {
                IamRepository.CredentialRow failed = passwordMismatches.get(0);
                Instant now = now();
                repository.recordFailedAttempt(failed.tenantId(), failed.userId(), now,
                        loginSecurity.maxFailedAttempts(), now.plus(loginSecurity.lockoutDuration()));
            }
            throw ApiException.unauthorized("LOGIN_FAILED", "登录失败。");
        }

        TenantContext context = TenantContext.ofHumanJwt(
                matched.tenantId(), matched.userId(), correlationId);
        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            if ("INACTIVE".equals(matched.userStatus())) {
                throw ApiException.forbidden("ACCOUNT_INACTIVE", "账户当前不可登录。");
            }
            if (matched.lockedUntil() != null && matched.lockedUntil().isAfter(now())) {
                throw ApiException.forbidden("ACCOUNT_TEMPORARILY_LOCKED", "账户暂时锁定。");
            }

            repository.updateLoginSuccess(matched.tenantId(), matched.userId());
            List<RoleCode> roles = parseRoles(repository.findGrantedRoleCodes(
                    matched.tenantId(), matched.userId()));
            List<PermissionCode> permissions = parsePermissions(repository.findGrantedPermissionCodes(
                    matched.tenantId(), matched.userId()));
            IssuedJwt jwt = jwtIssuer.issueHuman(
                    matched.tenantId(), matched.userId(), roles, permissions);
            IamDtos.CurrentUserResponse user = new IamDtos.CurrentUserResponse(
                    matched.userId(),
                    matched.username(),
                    currentDisplayName(matched.tenantId(), matched.userId(), matched),
                    "ACTIVE",
                    new IamDtos.TenantInfo(matched.tenantCode(), matched.tenantDisplayName()),
                    roles,
                    permissions);
            repository.insertAudit(matched.tenantId(), UUID.randomUUID(), "IAM_LOGIN", "USER",
                    matched.userId(), "SUCCEEDED", "USER", matched.userId(),
                    null, null, correlationId, now());
            return new IamDtos.AuthSessionResponse(jwt.token(), "Bearer", jwt.expiresAt(), user);
        }
    }

    public IamDtos.CurrentUserResponse currentUser() {
        TenantContext context = TenantContextHolder.current();
        JwtPrincipal principal = JwtPrincipalHolder.current();
        UUID userId = principal.humanUserId();
        IamRepository.CurrentUserRow row = repository.findCurrentUser(context.tenantId(), userId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "当前用户不存在。"));
        List<RoleCode> roles = parseRoles(repository.findGrantedRoleCodes(context.tenantId(), userId));
        List<PermissionCode> permissions = parsePermissions(
                repository.findGrantedPermissionCodes(context.tenantId(), userId));
        return new IamDtos.CurrentUserResponse(
                row.userId(), row.username(), row.displayName(), row.status(),
                new IamDtos.TenantInfo(row.tenantCode(), row.tenantDisplayName()),
                roles, permissions);
    }

    private String currentDisplayName(UUID tenantId, UUID userId, IamRepository.CredentialRow matched) {
        return repository.findCurrentUser(tenantId, userId)
                .map(IamRepository.CurrentUserRow::displayName)
                .orElse(matched.username());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static List<RoleCode> parseRoles(List<String> values) {
        return values.stream().map(RoleCode::valueOf).toList();
    }

    private static List<PermissionCode> parsePermissions(List<String> values) {
        return values.stream().map(PermissionCode::valueOf).toList();
    }
}
