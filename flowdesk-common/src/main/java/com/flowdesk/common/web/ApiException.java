package com.flowdesk.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.util.List;
import java.util.Map;

/**
 * 携带稳定 API 错误契约的业务或协议异常。
 */
public class ApiException extends RuntimeException {

    private final HttpStatusCode status;
    private final String code;
    private final boolean retryable;
    private final List<FieldViolation> details;
    private final Map<String, String> headers;

    /**
     * 保存 HTTP 状态、业务码及重试语义，并将字段明细与附加响应头复制为不可变集合；简化重载使用空明细或空头。
     */
    public ApiException(HttpStatusCode status, String code, String message, boolean retryable) {
        this(status, code, message, retryable, List.of(), Map.of());
    }

    /**
     * 保存 HTTP 状态、业务码及重试语义，并将字段明细与附加响应头复制为不可变集合；简化重载使用空明细或空头。
     */
    public ApiException(HttpStatusCode status, String code, String message, boolean retryable,
                        List<FieldViolation> details) {
        this(status, code, message, retryable, details, Map.of());
    }

    /**
     * 保存 HTTP 状态、业务码及重试语义，并将字段明细与附加响应头复制为不可变集合；简化重载使用空明细或空头。
     */
    public ApiException(HttpStatusCode status, String code, String message, boolean retryable,
                        List<FieldViolation> details, Map<String, String> headers) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
        this.details = List.copyOf(details);
        this.headers = Map.copyOf(headers);
    }

    /**
     * 返回业务异常对应的 HTTP 状态对象，供统一异常处理器使用。
     */
    public HttpStatusCode status() {
        return status;
    }

    /**
     * 返回 HTTP 状态数值，供错误信封写入流程使用。
     */
    public int statusValue() {
        return status.value();
    }

    /**
     * 返回稳定业务错误码，客户端据此识别错误而无需解析提示文本。
     */
    public String code() {
        return code;
    }

    /**
     * 返回协议约定的可重试标记，该标记不代替幂等或业务状态检查。
     */
    public boolean retryable() {
        return retryable;
    }

    /**
     * 返回字段级错误明细，由统一处理器写入错误信封。
     */
    public List<FieldViolation> details() {
        return details;
    }

    /**
     * 返回此错误附带的响应头，例如版本前置条件或缓存策略。
     */
    public Map<String, String> headers() {
        return headers;
    }

    /**
     * 构造不可重试的 400 错误，表示请求格式或参数协议不符合要求。
     */
    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message, false);
    }

    /**
     * 构造不可重试的 422 错误，并保留字段校验明细。
     */
    public static ApiException unprocessable(String code, String message, List<FieldViolation> details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, false, details);
    }

    /**
     * 构造不可重试的 401 错误，表示尚未建立可接受的身份。
     */
    public static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message, false);
    }

    /**
     * 构造不可重试的 403 错误，表示当前身份没有操作权限。
     */
    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message, false);
    }

    /**
     * 构造不可重试的 404 错误，统一表示资源不存在或对当前用户不可见。
     */
    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message, false);
    }

    /**
     * 构造不可重试的 409 错误，表示状态、唯一性或业务约束冲突。
     */
    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message, false);
    }

    /**
     * 构造 428 错误，提示写入请求缺少必要的版本前置条件。
     */
    public static ApiException preconditionRequired(String message) {
        return new ApiException(HttpStatusCode.valueOf(428), "PRECONDITION_REQUIRED", message, false);
    }

    /**
     * 构造 412 错误，表示版本前置条件已失效，需要刷新资源。
     */
    public static ApiException preconditionFailed(String code, String message, Map<String, String> headers) {
        return new ApiException(HttpStatusCode.valueOf(412), code, message, false, List.of(), headers);
    }

    /**
     * 构造内部错误的业务异常，由统一处理器输出稳定的错误信封。
     */
    public static ApiException internal(String message) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", message, false);
    }
}
