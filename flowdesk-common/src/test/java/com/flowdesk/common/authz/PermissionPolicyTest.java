package com.flowdesk.common.authz;

import com.flowdesk.common.security.JwtPrincipal;
import com.flowdesk.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证人类与服务主体的权限边界，覆盖租户一致性、角色能力上限及任一/全部权限组合判定。
 */
class PermissionPolicyTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    /**
     * 验证权限检查同时要求租户、操作者身份匹配，并受固定角色权限上限约束。
     */
    @Test
    void permissionRequiresMatchingTenantActorAndCeiling() {
        JwtPrincipal principal = JwtPrincipal.human(TENANT, USER,
                Set.of(RoleCode.TENANT_ADMIN), Set.of(PermissionCode.IAM_USER_MANAGE),
                Instant.now(), Instant.now().plusSeconds(60), UUID.randomUUID());
        TenantContext context = TenantContext.ofHumanJwt(TENANT, USER, UUID.randomUUID());

        PermissionPolicy.require(context, principal, PermissionCode.IAM_USER_MANAGE); // passes
    }

    /**
     * 验证上下文与认证主体的租户不一致时拒绝授权，不能借用其他租户的有效权限。
     */
    @Test
    void crossTenantContextAndPrincipalAreRejected() {
        JwtPrincipal principal = JwtPrincipal.human(TENANT, USER,
                Set.of(RoleCode.TENANT_ADMIN), Set.of(PermissionCode.IAM_USER_MANAGE),
                Instant.now(), Instant.now().plusSeconds(60), UUID.randomUUID());
        TenantContext wrongTenant = TenantContext.ofHumanJwt(
                UUID.fromString("22222222-2222-2222-2222-222222222222"), USER, UUID.randomUUID());

        assertThatThrownBy(() -> PermissionPolicy.require(
                wrongTenant, principal, PermissionCode.IAM_USER_MANAGE))
                .isInstanceOf(PermissionDeniedException.class);
    }

    /**
     * 验证实际权限取角色能力上限与已授予权限的交集，数据库多余授权不能突破角色边界。
     */
    @Test
    void roleCeilingIsIntersectedWithGrantedPermissions() {
        // A forged/stale combination where the permission is outside the role ceiling is denied.
        JwtPrincipal principal = JwtPrincipal.human(TENANT, USER,
                Set.of(RoleCode.REQUESTER), Set.of(PermissionCode.IAM_USER_MANAGE),
                Instant.now(), Instant.now().plusSeconds(60), UUID.randomUUID());
        TenantContext context = TenantContext.ofHumanJwt(TENANT, USER, UUID.randomUUID());

        assertThat(PermissionPolicy.effectivePermissions(principal))
                .doesNotContain(PermissionCode.IAM_USER_MANAGE);
        assertThatThrownBy(() -> PermissionPolicy.require(
                context, principal, PermissionCode.IAM_USER_MANAGE))
                .isInstanceOf(PermissionDeniedException.class);
    }

    /**
     * 验证服务 JWT 不能调用人类用户权限入口，防止服务主体被解释为管理用户。
     */
    @Test
    void serviceJwtCannotUseHumanPermissions() {
        JwtPrincipal principal = JwtPrincipal.service(TENANT, "flowdesk-ticket-service",
                Instant.now(), Instant.now().plusSeconds(60), UUID.randomUUID());
        TenantContext context = TenantContext.ofServiceJwt(TENANT, "flowdesk-ticket-service",
                UUID.randomUUID());

        assertThatThrownBy(() -> PermissionPolicy.require(
                context, principal, PermissionCode.TICKET_ASSIGN_TENANT))
                .isInstanceOf(PermissionDeniedException.class);
    }

    /**
     * 验证任一权限检查在当前主体至少具备一个候选权限时通过。
     */
    @Test
    void requireAnyPassesWhenAtLeastOnePermissionIsHeld() {
        // TenantAdmin holds IAM_USER_READ but not IAM_ELIGIBILITY_CANDIDATE_READ:
        // requireAny must pass, requireAll must fail.
        JwtPrincipal principal = JwtPrincipal.human(TENANT, USER,
                Set.of(RoleCode.TENANT_ADMIN), Set.of(PermissionCode.IAM_USER_READ),
                Instant.now(), Instant.now().plusSeconds(60), UUID.randomUUID());
        TenantContext context = TenantContext.ofHumanJwt(TENANT, USER, UUID.randomUUID());

        PermissionPolicy.requireAny(context, principal,
                PermissionCode.IAM_USER_READ, PermissionCode.IAM_ELIGIBILITY_CANDIDATE_READ);
        assertThatThrownBy(() -> PermissionPolicy.requireAll(context, principal,
                PermissionCode.IAM_USER_READ, PermissionCode.IAM_ELIGIBILITY_CANDIDATE_READ))
                .isInstanceOf(PermissionDeniedException.class);
    }

    /**
     * 验证未持有候选权限及空权限列表均拒绝访问，避免空条件意外放行。
     */
    @Test
    void requireAnyFailsWhenNoPermissionIsHeldAndEmptyListIsFailClosed() {
        JwtPrincipal principal = JwtPrincipal.human(TENANT, USER,
                Set.of(RoleCode.REQUESTER), Set.of(PermissionCode.TICKET_CREATE),
                Instant.now(), Instant.now().plusSeconds(60), UUID.randomUUID());
        TenantContext context = TenantContext.ofHumanJwt(TENANT, USER, UUID.randomUUID());

        assertThatThrownBy(() -> PermissionPolicy.requireAny(context, principal,
                PermissionCode.IAM_USER_READ, PermissionCode.IAM_USER_MANAGE))
                .isInstanceOf(PermissionDeniedException.class);
        // Fail-closed: an empty requireAny must never pass.
        assertThatThrownBy(() -> PermissionPolicy.requireAny(context, principal))
                .isInstanceOf(PermissionDeniedException.class);
        assertThatThrownBy(() -> PermissionPolicy.requireAll(context, principal))
                .isInstanceOf(PermissionDeniedException.class);
    }

    /**
     * 验证全部权限检查只有在主体满足每一项权限时通过。
     */
    @Test
    void requireAllPassesWhenEveryPermissionIsHeld() {
        JwtPrincipal principal = JwtPrincipal.human(TENANT, USER,
                Set.of(RoleCode.TENANT_ADMIN),
                Set.of(PermissionCode.IAM_USER_READ, PermissionCode.IAM_USER_MANAGE),
                Instant.now(), Instant.now().plusSeconds(60), UUID.randomUUID());
        TenantContext context = TenantContext.ofHumanJwt(TENANT, USER, UUID.randomUUID());

        PermissionPolicy.requireAll(context, principal,
                PermissionCode.IAM_USER_READ, PermissionCode.IAM_USER_MANAGE); // passes
    }

    /**
     * 验证恢复读取和人工重放权限仅对 ServiceDeskAdmin 开放，其他角色不因数据授权而突破上限。
     */
    @Test
    void recoveryReadAndReplayAreServiceDeskAdminOnly() {
        Set<PermissionCode> recoveryPermissions = Set.of(
                PermissionCode.RECOVERY_READ_TENANT,
                PermissionCode.RECOVERY_REPLAY_TENANT);

        assertThat(PermissionCode.RECOVERY_READ_TENANT.ceiling())
                .containsExactly(RoleCode.SERVICE_DESK_ADMIN);
        assertThat(PermissionCode.RECOVERY_REPLAY_TENANT.ceiling())
                .containsExactly(RoleCode.SERVICE_DESK_ADMIN);

        for (RoleCode role : RoleCode.values()) {
            JwtPrincipal principal = JwtPrincipal.human(TENANT, USER,
                    Set.of(role), recoveryPermissions,
                    Instant.now(), Instant.now().plusSeconds(60), UUID.randomUUID());

            if (role == RoleCode.SERVICE_DESK_ADMIN) {
                assertThat(PermissionPolicy.effectivePermissions(principal))
                        .containsExactlyInAnyOrderElementsOf(recoveryPermissions);
            } else {
                assertThat(PermissionPolicy.effectivePermissions(principal))
                        .doesNotContainAnyElementsOf(recoveryPermissions);
            }
        }
    }
}
