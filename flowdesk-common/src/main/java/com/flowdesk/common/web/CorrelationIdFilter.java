package com.flowdesk.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Normalizes X-Correlation-Id for every request.
 *
 * <p>Public entry semantics are owned by Gateway, but each service also runs this filter
 * so direct calls still receive a safe tracing id. Service-internal paths are strict:
 * malformed/duplicated correlation is a protocol error (ERR-015).</p>
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final List<String> strictPaths;
    private final ObjectMapper mapper;

    public CorrelationIdFilter(List<String> strictPaths) {
        this(strictPaths, new ObjectMapper());
    }

    public CorrelationIdFilter(List<String> strictPaths, ObjectMapper mapper) {
        this.strictPaths = List.copyOf(strictPaths);
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getParameterMap().containsKey("tenantId")
                || request.getParameterMap().containsKey("tenant_id")) {
            ApiErrorWriter.write(response, 400, "TENANT_INPUT_FORBIDDEN",
                    "禁止在 query 中提交 tenantId。", false, UUID.randomUUID(), List.of(), mapper);
            return;
        }
        List<String> values = request.getHeaders("X-Correlation-Id") == null
                ? List.of()
                : list(request.getHeaders("X-Correlation-Id"));
        String uri = request.getRequestURI();
        UUID correlationId = null;
        if (values.size() == 1 && isUuid(values.get(0))) {
            correlationId = UUID.fromString(values.get(0));
        } else if (isStrict(uri)) {
            ApiErrorWriter.write(response, 400, "CORRELATION_ID_INVALID",
                    "受保护服务入口的 X-Correlation-Id 缺失、重复或无效。", false, UUID.randomUUID(), List.of(), mapper);
            return;
        }
        if (correlationId == null) {
            correlationId = UUID.randomUUID();
        }
        request.setAttribute(FlowDeskRequestAttributes.CORRELATION_ID, correlationId);
        response.setHeader("X-Correlation-Id", correlationId.toString());
        filterChain.doFilter(request, response);
    }

    private boolean isStrict(String uri) {
        for (String pattern : strictPaths) {
            if (pathMatcher.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static List<String> list(java.util.Enumeration<String> enumeration) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        while (enumeration.hasMoreElements()) {
            values.add(enumeration.nextElement());
        }
        return values;
    }
}
