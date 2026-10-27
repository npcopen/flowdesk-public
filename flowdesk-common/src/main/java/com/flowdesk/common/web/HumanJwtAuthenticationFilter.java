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
 * 对业务服务收到的人类 JWT 再次执行本地验签。
 * 验证成功后建立 HUMAN_JWT 租户上下文，并在请求结束或异常时清理，防止线程复用导致身份串用。
 */
public class HumanJwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtVerifier verifier;
    private final List<String> publicPaths;
    private final List<String> serviceJwtPaths;
    private final ObjectMapper mapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * 注入验签器并复制公开及服务专用路径，保存用于认证失败响应的 JSON 映射器。
     */
    public HumanJwtAuthenticationFilter(JwtVerifier verifier,
                                        List<String> publicPaths,
                                        List<String> serviceJwtPaths) {
        this(verifier, publicPaths, serviceJwtPaths, new ObjectMapper());
    }

    /**
     * 注入验签器并复制公开及服务专用路径，保存用于认证失败响应的 JSON 映射器。
     */
    public HumanJwtAuthenticationFilter(JwtVerifier verifier,
                                        List<String> publicPaths,
                                        List<String> serviceJwtPaths,
                                        ObjectMapper mapper) {
        this.verifier = verifier;
        this.publicPaths = List.copyOf(publicPaths);
        this.serviceJwtPaths = List.copyOf(serviceJwtPaths);
        this.mapper = mapper;
    }

    /**
     * 根据配置路径决定是否执行人类 JWT 校验，避免不同身份入口混用。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return matches(publicPaths, uri) || matches(serviceJwtPaths, uri);
    }

    /**
     * 提取唯一 Bearer 并验证人类身份，失败返回统一 401；成功建立租户主体作用域，并在结束时清理线程状态。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        UUID correlationId = correlationId(request);
        String token = bearerToken(request);
        if (token == null) {
            ApiErrorWriter.write(response, 401, "AUTHENTICATION_REQUIRED",
                    "缺少人类 Bearer 身份。", false, correlationId, List.of(), mapper);
            return;
        }
        final JwtPrincipal principal;
        try {
            principal = verifier.verifyHuman(token);
        } catch (JwtVerificationException e) {
            String code = e.kind() == JwtVerificationException.Kind.EXPIRED
                    ? "TOKEN_EXPIRED" : "AUTHENTICATION_FAILED";
            ApiErrorWriter.write(response, 401, code, "JWT 身份验证失败。", false, correlationId, List.of(), mapper);
            return;
        }
        TenantContext context = principal.toHumanTenantContext(correlationId);
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
