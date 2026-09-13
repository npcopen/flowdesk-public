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
 * Local JWT re-verification for MVC fact services.
 *
 * <p>Gateway first-check is never trusted. This filter performs the second cryptographic
 * verification, installs a HUMAN_JWT TenantContext and always clears it, including on
 * downstream exceptions.</p>
 */
public class HumanJwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtVerifier verifier;
    private final List<String> publicPaths;
    private final List<String> serviceJwtPaths;
    private final ObjectMapper mapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public HumanJwtAuthenticationFilter(JwtVerifier verifier,
                                        List<String> publicPaths,
                                        List<String> serviceJwtPaths) {
        this(verifier, publicPaths, serviceJwtPaths, new ObjectMapper());
    }

    public HumanJwtAuthenticationFilter(JwtVerifier verifier,
                                        List<String> publicPaths,
                                        List<String> serviceJwtPaths,
                                        ObjectMapper mapper) {
        this.verifier = verifier;
        this.publicPaths = List.copyOf(publicPaths);
        this.serviceJwtPaths = List.copyOf(serviceJwtPaths);
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return matches(publicPaths, uri) || matches(serviceJwtPaths, uri);
    }

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

    private boolean matches(List<String> patterns, String uri) {
        for (String pattern : patterns) {
            if (pathMatcher.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    private static UUID correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(FlowDeskRequestAttributes.CORRELATION_ID);
        return value instanceof UUID uuid ? uuid : UUID.randomUUID();
    }

    private static List<String> enumerationList(Enumeration<String> enumeration) {
        List<String> values = new ArrayList<>();
        while (enumeration.hasMoreElements()) {
            values.add(enumeration.nextElement());
        }
        return values;
    }
}
