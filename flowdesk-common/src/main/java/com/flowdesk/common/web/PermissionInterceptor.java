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
 * 基于本地复验的 JWT 和可信租户上下文执行 {@link RequiresPermission} 校验。
 * 未声明该注解时不拦截；具有多种可选权限的接口可直接调用 {@link PermissionPolicy}。
 */
public class PermissionInterceptor implements HandlerInterceptor {

    /**
     * 优先读取方法再读取类的权限声明，对已验证人类主体检查授权；无声明或非控制器处理器直接放行。
     */
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
