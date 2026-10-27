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
 * 服务本地的资源授权策略。
 * 人类 JWT 中的角色和权限只提供初步身份依据；实际授权取固定角色权限上限与令牌授权的交集，
 * 业务服务仍需结合租户、资源归属和当前状态完成校验。
 */
public final class PermissionPolicy {

    /** 禁止实例化；授权判断只通过静态入口执行，避免出现持有过期主体信息的策略对象。 */
    private PermissionPolicy() {
    }

    /** 校验当前上下文来自人类 JWT，其租户和主体与已验证令牌完全一致，且同时满足权限与角色限制。 */
    public static void requireCurrentPrincipal(PermissionCode permission) {
        require(TenantContextHolder.current(), JwtPrincipalHolder.current(), permission);
    }

    /**
     * 校验单项权限，同时复用全部权限入口中的人类身份、租户一致性和角色上限检查。
     * 任一条件不满足时抛出 PermissionDeniedException，交由统一异常处理器转换为拒绝访问响应。
     */
    public static void require(TenantContext context, JwtPrincipal principal, PermissionCode permission) {
        requireAll(context, principal, permission);
    }

    /** 要求当前主体至少拥有给定权限之一，且权限未超出角色上限；空列表无法满足任何权限，因此直接拒绝。 */
    public static void requireAny(TenantContext context, JwtPrincipal principal, PermissionCode... permissions) {
        requireHumanPrincipal(context, principal);
        for (PermissionCode permission : permissions) {
            if (isAllowedTrusted(context, principal, permission)) {
                return;
            }
        }
        throw new PermissionDeniedException("Missing any of the required permissions");
    }

    /** 要求当前主体拥有全部给定权限，且均未超出角色上限；空列表通常表示调用错误，因此直接拒绝。 */
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

    /** 确认上下文和令牌均表示同一租户的同一人类用户，拒绝服务令牌、缺失身份和上下文错配。 */
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

    /** 在调用方完成身份一致性校验后，检查权限是否同时出现在 JWT 授权集合和任一角色的固定上限中。 */
    private static boolean isAllowedTrusted(TenantContext context, JwtPrincipal principal,
                                             PermissionCode permission) {
        // 调用方已验证人类租户上下文与 JWT 的租户、主体一致性，此处只计算有效权限。
        boolean ceilingMatched = principal.roles().stream().anyMatch(permission::isAllowedFor);
        return ceilingMatched && principal.permissions().contains(permission);
    }

    /**
     * 有效权限集合：固定角色权限上限与 JWT 已授予权限的交集。
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

    /** 判断人类主体是否拥有候选权限中的任意一项；主体缺失、服务身份或候选列表为空时返回 false。 */
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

    /** 提取已经建立的人类 JWT 上下文中的用户标识；其他来源的上下文会触发权限拒绝异常。 */
    public static UUID humanUserId(TenantContext context) {
        if (context == null || context.source() != TenantContextSource.HUMAN_JWT
                || !(context.actor() instanceof ActorRef.Human human)) {
            throw new PermissionDeniedException("HUMAN_JWT TenantContext required");
        }
        return human.userId();
    }
}
