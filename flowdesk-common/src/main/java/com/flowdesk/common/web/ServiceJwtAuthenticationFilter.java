package com.flowdesk.common.web;

import com.flowdesk.common.security.JwtPrincipal;
import com.flowdesk.common.security.JwtVerificationException;
import com.flowdesk.common.security.JwtVerifier;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

/**
 * 为受保护的内部接口验证服务身份 JWT，例如 IAM 的资格预检查接口。
 */
public class ServiceJwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtVerifier verifier;
    private final List<String> serviceJwtPaths;
    private final List<String> allowedServiceIds;
    private final ObjectMapper mapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * 注入服务验签器，复制内部路径与服务白名单，保存认证失败响应所需映射器。
     */
    public ServiceJwtAuthenticationFilter(JwtVerifier verifier,
                                          List<String> serviceJwtPaths,
                                          List<String> allowedServiceIds) {
        this(verifier, serviceJwtPaths, allowedServiceIds, new ObjectMapper());
    }

    /**
     * 注入服务验签器，复制内部路径与服务白名单，保存认证失败响应所需映射器。
     */
    public ServiceJwtAuthenticationFilter(JwtVerifier verifier,
                                          List<String> serviceJwtPaths,
                                          List<String> allowedServiceIds,
                                          ObjectMapper mapper) {
        this.verifier = verifier;
        this.serviceJwtPaths = List.copyOf(serviceJwtPaths);
        this.allowedServiceIds = List.copyOf(allowedServiceIds);
        this.mapper = mapper;
    }

    /**
     * 根据配置路径决定是否执行服务 JWT 校验，避免不同身份入口混用。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (serviceJwtPaths.isEmpty()) {
            return true;
        }
        return !matches(serviceJwtPaths, request.getRequestURI());
    }

    /**
     * 提取唯一 Bearer、复验服务 JWT，并要求服务名位于当前内部接口允许名单；空名单同样拒绝访问。
     * 认证失败返回统一 401，成功则建立服务租户作用域，并在调用结束时清理线程主体。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        UUID correlationId = correlationId(request);
        String token = bearerToken(request);
        if (token == null) {
            ApiErrorWriter.write(response, 401, "SERVICE_IDENTITY_REQUIRED",
                    "内部资格接口缺少受认证服务身份。", false, correlationId, List.of(), mapper);
            return;
        }
        final JwtPrincipal principal;
        try {
            principal = verifier.verifyService(token);
        } catch (JwtVerificationException e) {
            String code = e.kind() == JwtVerificationException.Kind.EXPIRED
                    ? "TOKEN_EXPIRED"
                    : e.kind() == JwtVerificationException.Kind.TYPE_REJECTED
                            ? "SERVICE_IDENTITY_REQUIRED"
                            : "AUTHENTICATION_FAILED";
            ApiErrorWriter.write(response, 401, code, "服务 JWT 身份验证失败。", false, correlationId, List.of(), mapper);
            return;
        }
        if (allowedServiceIds.isEmpty() || !allowedServiceIds.contains(principal.serviceId())) {
            ApiErrorWriter.write(response, 401, "SERVICE_IDENTITY_REQUIRED",
                    "调用服务不在该内部接口允许名单中。", false, correlationId, List.of(), mapper);
            return;
        }
        TenantContext context = principal.toServiceTenantContext(correlationId);
        request.setAttribute(FlowDeskRequestAttributes.JWT_PRINCIPAL, principal);
        JwtPrincipalHolder.set(principal);
        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            filterChain.doFilter(request, response);
        } finally {
            JwtPrincipalHolder.clear();
        }
    }

    /**
     * 仅接受唯一且格式为 Bearer 的 Authorization 头，缺失、重复或类型错误返回 null。
     */
    private String bearerToken(HttpServletRequest request) {
        List<String> headers = enumerationList(request.getHeaders("Authorization"));
        if (headers.size() != 1) {
            return null;
        }
        String header = headers.get(0);
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring("Bearer ".length()).trim();
    }

    /**
     * 按配置的 Ant 路径模式匹配 URI，命中任一模式即返回 true。
     */
    private boolean matches(List<String> patterns, String uri) {
        for (String pattern : patterns) {
            if (pathMatcher.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取上游写入请求属性的 UUID，缺失则生成新标识供认证错误追踪。
     */
    private static UUID correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(FlowDeskRequestAttributes.CORRELATION_ID);
        return value instanceof UUID uuid ? uuid : UUID.randomUUID();
    }

    /**
     * 将请求头枚举复制为列表，供认证入口检测重复 Authorization 头。
     */
    private static List<String> enumerationList(Enumeration<String> enumeration) {
        List<String> values = new ArrayList<>();
        while (enumeration.hasMoreElements()) {
            values.add(enumeration.nextElement());
        }
        return values;
    }
}
