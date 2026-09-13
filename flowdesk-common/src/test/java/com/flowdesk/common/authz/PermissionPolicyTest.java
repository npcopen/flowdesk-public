package com.flowdesk.common.authz;

import com.flowdesk.common.security.JwtPrincipal;
import com.flowdesk.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PermissionPolicyTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void permissionRequiresMatchingTenantActorAndCeiling() {
        JwtPrincipal principal = JwtPrincipal.human(TENANT, USER,
                Set.of(RoleCode.TENANT_ADMIN), Set.of(PermissionCode.IAM_USER_MANAGE),
                Instant.now(), Instant.now().plusSeconds(60), UUID.randomUUID());
        TenantContext context = TenantContext.ofHumanJwt(TENANT, USER, UUID.randomUUID());

        PermissionPolicy.require(context, principal, PermissionCode.IAM_USER_MANAGE); // passes
    }

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
