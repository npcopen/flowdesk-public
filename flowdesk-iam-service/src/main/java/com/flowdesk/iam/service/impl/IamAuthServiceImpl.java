package com.flowdesk.iam.service.impl;

import com.flowdesk.iam.model.*;
import com.flowdesk.iam.service.*;

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
import com.flowdesk.iam.dto.IamDtos;
import com.flowdesk.iam.mapper.AccessMapper;
import com.flowdesk.iam.mapper.AuditMapper;
import com.flowdesk.iam.mapper.CredentialMapper;
import com.flowdesk.iam.mapper.UserMapper;
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
 * 处理登录与当前用户查询；从 IAM 权威数据加载身份、角色和权限，并签发登录 JWT。
 */
@Service
public class IamAuthServiceImpl implements IamAuthService {

    private final CredentialMapper credentialRepository;
    private final CredentialFailureService credentialFailureService;
    private final UserMapper userRepository;
    private final AccessMapper accessRepository;
    private final AuditMapper auditRepository;
    private final PasswordHashService passwordHashService;
    private final JwtIssuer jwtIssuer;
    private final LoginSecurityProperties loginSecurity;
    private final Clock clock;

    /**
     * 组装登录所需的持久化、密码校验与令牌签发能力，并使用 UTC 系统时钟判断锁定期限。
     * 需要固定时间进行测试时，可使用显式接收 Clock 的构造方法。
     */
    public IamAuthServiceImpl(CredentialMapper credentialRepository,
                          CredentialFailureService credentialFailureService,
                          UserMapper userRepository,
                          AccessMapper accessRepository,
                          AuditMapper auditRepository,
                          PasswordHashService passwordHashService,
                          JwtIssuer jwtIssuer,
                          LoginSecurityProperties loginSecurity) {
        this(credentialRepository, credentialFailureService, userRepository, accessRepository, auditRepository,
                passwordHashService, jwtIssuer, loginSecurity, Clock.systemUTC());
    }

    /**
     * 注入登录流程依赖和统一时钟；登录失败计数交给独立服务持久化，避免随登录异常回滚。
     * 所有租户内授权查询都在凭证唯一匹配并建立可信租户上下文之后执行。
     */
    @Autowired
    public IamAuthServiceImpl(CredentialMapper credentialRepository,
                          CredentialFailureService credentialFailureService,
                          UserMapper userRepository,
                          AccessMapper accessRepository,
                          AuditMapper auditRepository,
                          PasswordHashService passwordHashService,
                          JwtIssuer jwtIssuer,
                          LoginSecurityProperties loginSecurity,
                          Clock clock) {
        this.credentialRepository = credentialRepository;
        this.credentialFailureService = credentialFailureService;
        this.userRepository = userRepository;
        this.accessRepository = accessRepository;
        this.auditRepository = auditRepository;
        this.passwordHashService = passwordHashService;
        this.jwtIssuer = jwtIssuer;
        this.loginSecurity = loginSecurity;
        this.clock = clock;
    }

    /**
     * 校验租户代码、用户名和密码，在唯一匹配且账户可用时签发登录 JWT。
     * 密码无匹配或多账户匹配统一拒绝登录；唯一密码错误候选会累加失败次数，成功则清零并写入审计。
     *
     * @param request 包含租户代码、用户名与明文密码的登录请求
     * @param correlationId 贯穿登录处理及审计记录的关联标识
     * @return 访问令牌、到期时间及从 IAM 当前数据加载的用户身份与授权
     */
    @Transactional
    public IamDtos.AuthSessionResponse login(IamDtos.LoginRequest request, UUID correlationId) {
        List<UUID> tenantIds = credentialRepository.findTenantIdsByCode(request.tenantCode());
        CredentialRow matched = null;
        int matchCount = 0;
        List<CredentialRow> passwordMismatches = new ArrayList<>();
        int passwordComparisons = 0;
        for (UUID tenantId : tenantIds) {
            var credential = credentialRepository.findCredential(tenantId, request.username()).orElse(null);
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
        // 无凭证候选时仍执行一次密码派生，降低通过响应耗时探测用户名的可能。
        if (passwordComparisons == 0) {
            passwordHashService.consumeTimingDummy(request.password());
        }
        if (matchCount != 1 || matched == null) {
            // 未匹配与多账户匹配统一返回登录失败，避免泄露账户或租户是否存在。
            // 只有唯一密码不匹配账户时才累加失败次数，避免将模糊尝试归因到错误账户。
            if (passwordMismatches.size() == 1) {
                CredentialRow failed = passwordMismatches.get(0);
                Instant now = now();
                credentialFailureService.recordFailedAttempt(failed.tenantId(), failed.userId(), now,
                        loginSecurity.maxFailedAttempts(), now.plus(loginSecurity.lockoutDuration()));
            }
            throw ApiException.unauthorized("LOGIN_FAILED", "登录失败。");
        }

        // 仅在凭证唯一匹配后建立可信租户上下文，后续角色、权限和审计均绑定该租户。
        TenantContext context = TenantContext.ofHumanJwt(
                matched.tenantId(), matched.userId(), correlationId);
        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            if ("INACTIVE".equals(matched.userStatus())) {
                throw ApiException.forbidden("ACCOUNT_INACTIVE", "账户当前不可登录。");
            }
            if (matched.lockedUntil() != null && matched.lockedUntil().isAfter(now())) {
                throw ApiException.forbidden("ACCOUNT_TEMPORARILY_LOCKED", "账户暂时锁定。");
            }

            credentialRepository.updateLoginSuccess(matched.tenantId(), matched.userId());
            List<RoleCode> roles = parseRoles(accessRepository.findGrantedRoleCodes(
                    matched.tenantId(), matched.userId()));
            List<PermissionCode> permissions = parsePermissions(accessRepository.findGrantedPermissionCodes(
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
            auditRepository.insertAudit(matched.tenantId(), UUID.randomUUID(), "IAM_LOGIN", "USER",
                    matched.userId(), "SUCCEEDED", "USER", matched.userId(),
                    null, null, correlationId, now());
            return new IamDtos.AuthSessionResponse(jwt.token(), "Bearer", jwt.expiresAt(), user);
        }
    }

    /**
     * 按已认证主体和当前租户重新读取用户信息、角色及权限。
     * 返回最新 IAM 授权事实，避免直接把 JWT 内可能已过期的授权快照作为当前用户资料。
     */
    public IamDtos.CurrentUserResponse currentUser() {
        TenantContext context = TenantContextHolder.current();
        JwtPrincipal principal = JwtPrincipalHolder.current();
        UUID userId = principal.humanUserId();
        CurrentUserRow row = userRepository.findCurrentUser(context.tenantId(), userId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "当前用户不存在。"));
        List<RoleCode> roles = parseRoles(accessRepository.findGrantedRoleCodes(context.tenantId(), userId));
        List<PermissionCode> permissions = parsePermissions(
                accessRepository.findGrantedPermissionCodes(context.tenantId(), userId));
        return new IamDtos.CurrentUserResponse(
                row.userId(), row.username(), row.displayName(), row.status(),
                new IamDtos.TenantInfo(row.tenantCode(), row.tenantDisplayName()),
                roles, permissions);
    }

    /** 从用户资料读取显示名称；资料查询无结果时回退为已匹配凭证中的用户名。 */
    private String currentDisplayName(UUID tenantId, UUID userId, CredentialRow matched) {
        return userRepository.findCurrentUser(tenantId, userId)
                .map(CurrentUserRow::displayName)
                .orElse(matched.username());
    }

    /** 从注入时钟读取当前时间并截断到微秒，与数据库时间精度保持一致。 */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** 将数据库角色代码转换为领域枚举；未知代码直接抛错，避免静默丢弃异常授权数据。 */
    private static List<RoleCode> parseRoles(List<String> values) {
        return values.stream().map(RoleCode::valueOf).toList();
    }

    /** 将数据库权限代码转换为领域枚举，供 JWT 签发及当前用户响应共同使用。 */
    private static List<PermissionCode> parsePermissions(List<String> values) {
        return values.stream().map(PermissionCode::valueOf).toList();
    }
}
