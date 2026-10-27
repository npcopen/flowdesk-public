package com.flowdesk.common.web;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * 输出固定结构的错误响应，避免泄露异常堆栈、密钥或其他内部信息。
 */
public final class ApiErrorWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 禁止直接实例化该工具类型，统一通过静态方法或工厂入口使用。
     */
    private ApiErrorWriter() {
    }

    /**
     * 向 Servlet 响应写入统一 JSON 错误信封，并设置 HTTP 状态与关联头；简化重载复用完整写入入口。
     */
    public static void write(HttpServletResponse response,
                             int status,
                             String code,
                             String message,
                             boolean retryable,
                             UUID correlationId) throws IOException {
        write(response, status, code, message, retryable, correlationId, List.of());
    }

    /**
     * 向 Servlet 响应写入统一 JSON 错误信封，并设置 HTTP 状态与关联头；简化重载复用完整写入入口。
     */
    public static void write(HttpServletResponse response,
                             int status,
                             String code,
                             String message,
                             boolean retryable,
                             UUID correlationId,
                             List<FieldViolation> details) throws IOException {
        write(response, status, code, message, retryable, correlationId, details, MAPPER);
    }

    /**
     * 使用调用方提供的 {@link ObjectMapper} 输出错误响应，通常由 Spring 配置，
     * 确保过滤器的错误响应与 MVC 响应使用相同的 JSON 规则。
     */
    public static void write(HttpServletResponse response,
                             int status,
                             String code,
                             String message,
                             boolean retryable,
                             UUID correlationId,
                             List<FieldViolation> details,
                             ObjectMapper mapper) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("X-Correlation-Id", correlationId.toString());
        ErrorEnvelope envelope = new ErrorEnvelope(new ApiError(
                code, message, correlationId, retryable, details.isEmpty() ? null : details));
        mapper.writeValue(response.getWriter(), envelope);
    }
}
