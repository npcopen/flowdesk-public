package com.flowdesk.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.util.List;
import java.util.Map;

/** Domain/protocol failure carrying the stable API error contract. */
public class ApiException extends RuntimeException {

    private final HttpStatusCode status;
    private final String code;
    private final boolean retryable;
    private final List<FieldViolation> details;
    private final Map<String, String> headers;

    public ApiException(HttpStatusCode status, String code, String message, boolean retryable) {
        this(status, code, message, retryable, List.of(), Map.of());
    }

    public ApiException(HttpStatusCode status, String code, String message, boolean retryable,
                        List<FieldViolation> details) {
        this(status, code, message, retryable, details, Map.of());
    }

    public ApiException(HttpStatusCode status, String code, String message, boolean retryable,
                        List<FieldViolation> details, Map<String, String> headers) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
        this.details = List.copyOf(details);
        this.headers = Map.copyOf(headers);
    }

    public HttpStatusCode status() {
        return status;
    }

    public int statusValue() {
        return status.value();
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public List<FieldViolation> details() {
        return details;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message, false);
    }

    public static ApiException unprocessable(String code, String message, List<FieldViolation> details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, false, details);
    }

    public static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message, false);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message, false);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message, false);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message, false);
    }

    public static ApiException preconditionRequired(String message) {
        return new ApiException(HttpStatusCode.valueOf(428), "PRECONDITION_REQUIRED", message, false);
    }

    public static ApiException preconditionFailed(String code, String message, Map<String, String> headers) {
        return new ApiException(HttpStatusCode.valueOf(412), code, message, false, List.of(), headers);
    }

    public static ApiException internal(String message) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", message, false);
    }
}
