package com.flowdesk.common.web;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.PermissionDeniedException;
import com.flowdesk.common.authz.PermissionPolicy;
import com.flowdesk.common.security.JwtPrincipal;
import com.flowdesk.common.tenant.NoTenantContextException;
import com.flowdesk.common.tenant.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequiresPermission} against the locally re-verified JWT and trusted
 * TenantContext. Without the annotation this interceptor does nothing; business endpoints
 * must opt in, and may use {@link PermissionPolicy} directly for alternative roles.
 */
public class PermissionInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequiresPermission annotation = handlerMethod.getMethodAnnotation(RequiresPermission.class);
        if (annotation == null) {
            annotation = handlerMethod.getBeanType().getAnnotation(RequiresPermission.class);
        }
        if (annotation == null) {
            return true;
        }
        JwtPrincipal principal = JwtPrincipalHolder.currentOrNull();
        if (principal == null) {
            Object attr = request.getAttribute(FlowDeskRequestAttributes.JWT_PRINCIPAL);
            if (attr instanceof JwtPrincipal stored) {
                principal = stored;
            }
        }
        if (principal == null) {
            throw new PermissionDeniedException("No verified JWT principal");
        }
        try {
            PermissionCode[] permissions = annotation.value();
            if (permissions.length == 0) {
                throw new PermissionDeniedException("Empty permission list is not allowed");
            }
            PermissionPolicy.requireAll(TenantContextHolder.current(), principal, permissions);
            return true;
        } catch (PermissionDeniedException | NoTenantContextException e) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED", "当前角色或权限不足。", false);
        }
    }
}
