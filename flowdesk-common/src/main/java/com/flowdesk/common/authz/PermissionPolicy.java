package com.flowdesk.common.authz;

import com.flowdesk.common.security.JwtPrincipal;
import com.flowdesk.common.security.JwtTokenType;
import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.tenant.TenantContextSource;
import com.flowdesk.common.web.JwtPrincipalHolder;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Local resource authorization policy (API-SPEC §3.2).
 *
 * <p>Human JWT roles/permissions are only coarse identity input. A service must also
 * check tenant, resource ownership and state. This policy enforces the fixed role
 * ceiling intersected with the verified permission grants.</p>
 */
public final class PermissionPolicy {

    private PermissionPolicy() {
    }

    /**
     * Checks that the current trusted context is a human JWT context whose tenant and
     * subject exactly match the verified JWT, and that the principal has the required
     * permission plus an allowed role.
     */
    public static void requireCurrentPrincipal(PermissionCode permission) {
        require(TenantContextHolder.current(), JwtPrincipalHolder.current(), permission);
    }

    public static void require(TenantContext context, JwtPrincipal principal, PermissionCode permission) {
        requireAll(context, principal, permission);
    }

    /**
     * Requires the current principal to hold at least one of the given permissions
     * (role-ceiling intersected with granted claims). An empty list never passes
     * (fail-closed): there is no permission that could satisfy it.
     */
    public static void requireAny(TenantContext context, JwtPrincipal principal, PermissionCode... permissions) {
        requireHumanPrincipal(context, principal);
        for (PermissionCode permission : permissions) {
            if (isAllowedTrusted(context, principal, permission)) {
                return;
            }
        }
        throw new PermissionDeniedException("Missing any of the required permissions");
    }

    /**
     * Requires the current principal to hold all of the given permissions
     * (role-ceiling intersected with granted claims). At least one permission must be
     * requested; an empty list is fail-closed because it is usually a caller bug.
     */
    public static void requireAll(TenantContext context, JwtPrincipal principal, PermissionCode... permissions) {
        requireHumanPrincipal(context, principal);
        if (permissions.length == 0) {
            throw new PermissionDeniedException("At least one permission is required");
        }
        for (PermissionCode permission : permissions) {
            if (!isAllowedTrusted(context, principal, permission)) {
                throw new PermissionDeniedException("Missing permission " + permission);
            }
        }
    }

    private static void requireHumanPrincipal(TenantContext context, JwtPrincipal principal) {
        if (context == null || principal == null) {
            throw new PermissionDeniedException("No authenticated human principal in scope");
        }
        if (context.source() != TenantContextSource.HUMAN_JWT
                || !(context.actor() instanceof ActorRef.Human human)) {
            throw new PermissionDeniedException("Permission requires a HUMAN_JWT TenantContext");
        }
        if (principal.tokenType() != JwtTokenType.HUMAN) {
            throw new PermissionDeniedException("Permission requires a human JWT principal");
        }
        if (!context.tenantId().equals(principal.tenantId())
                || !human.userId().equals(principal.humanUserId())) {
            throw new PermissionDeniedException("TenantContext and JWT principal do not match");
        }
    }

    private static boolean isAllowedTrusted(TenantContext context, JwtPrincipal principal,
                                             PermissionCode permission) {
        // Callers have already validated the human TenantContext/JWT pair once.
        boolean ceilingMatched = principal.roles().stream().anyMatch(permission::isAllowedFor);
        return ceilingMatched && principal.permissions().contains(permission);
    }

    /**
     * Effective permission set: ceiling intersection with granted claims.
     */
    public static Set<PermissionCode> effectivePermissions(JwtPrincipal principal) {
        if (principal == null || principal.tokenType() != JwtTokenType.HUMAN) {
            return Set.of();
        }
        EnumSet<PermissionCode> result = EnumSet.noneOf(PermissionCode.class);
        for (PermissionCode permission : principal.permissions()) {
            if (principal.roles().stream().anyMatch(permission::isAllowedFor)) {
                result.add(permission);
            }
        }
        return Set.copyOf(result);
    }

    public static boolean hasAny(JwtPrincipal principal, PermissionCode... permissions) {
        if (principal == null || principal.tokenType() != JwtTokenType.HUMAN) {
            return false;
        }
        Set<PermissionCode> effective = effectivePermissions(principal);
        for (PermissionCode permission : permissions) {
            if (effective.contains(permission)) {
                return true;
            }
        }
        return false;
    }

    public static UUID humanUserId(TenantContext context) {
        if (context == null || context.source() != TenantContextSource.HUMAN_JWT
                || !(context.actor() instanceof ActorRef.Human human)) {
            throw new PermissionDeniedException("HUMAN_JWT TenantContext required");
        }
        return human.userId();
    }
}
