package com.flowdesk.common.web;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Renders the fixed SCHEMA-002 error envelope without leaking stack/secret details. */
public final class ApiErrorWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApiErrorWriter() {
    }

    public static void write(HttpServletResponse response,
                             int status,
                             String code,
                             String message,
                             boolean retryable,
                             UUID correlationId) throws IOException {
        write(response, status, code, message, retryable, correlationId, List.of());
    }

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
     * Renders the envelope with the caller-supplied (normally Spring-configured)
     * {@link ObjectMapper} so error responses and MVC responses share the same JSON
     * configuration.
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
