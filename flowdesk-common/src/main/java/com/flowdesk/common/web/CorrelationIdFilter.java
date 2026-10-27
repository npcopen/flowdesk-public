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
 * 为每个请求规范化 X-Correlation-Id。
 * Gateway 定义公开入口规则，各业务服务也执行本地检查以支持直接调用；
 * 内部服务路径严格拒绝格式错误或重复的关联标识，避免破坏调用链追踪。
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final List<String> strictPaths;
    private final ObjectMapper mapper;

    /**
     * 复制严格路径配置并保存 JSON 映射器，简化重载使用默认映射器输出错误响应。
     */
    public CorrelationIdFilter(List<String> strictPaths) {
        this(strictPaths, new ObjectMapper());
    }

    /**
     * 复制严格路径配置并保存 JSON 映射器，简化重载使用默认映射器输出错误响应。
     */
    public CorrelationIdFilter(List<String> strictPaths, ObjectMapper mapper) {
        this.strictPaths = List.copyOf(strictPaths);
        this.mapper = mapper;
    }

    /**
     * 校验或生成关联 UUID，严格路径拒绝缺失或歧义请求头，再把选定标识写入请求属性及响应头。
     */
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

    /**
     * 判断 URI 是否命中必须严格校验关联标识的路径模式。
     */
    private boolean isStrict(String uri) {
        for (String pattern : strictPaths) {
            if (pathMatcher.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查标识是否具有合法 UUID 格式，格式错误时返回 false。
     */
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

    /**
     * 将请求头枚举复制为列表，便于检测重复头及单值协议。
     */
    private static List<String> list(java.util.Enumeration<String> enumeration) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        while (enumeration.hasMoreElements()) {
            values.add(enumeration.nextElement());
        }
        return values;
    }
}
