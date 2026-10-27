package com.flowdesk.common.web;

import com.flowdesk.common.authz.PermissionDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 统一处理 MVC 异常并生成 SCHEMA-002 错误响应。
 * 对外消息保持简洁、稳定，不暴露堆栈、SQL、密钥或内部服务拓扑。
 */
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiExceptionHandler {

    private static final java.util.regex.Pattern UNRECOGNIZED_PROPERTY =
            java.util.regex.Pattern.compile("property \"([^\"]+)\"");

    /**
     * 保留业务异常的状态、错误码、重试标记、字段明细及响应头，转换为统一错误响应。
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorEnvelope> handleApiException(ApiException e, HttpServletRequest request) {
        return render(request, e.statusValue(), e.code(), e.getMessage(), e.retryable(), e.details(), e.headers());
    }

    /**
     * 将授权拒绝统一映射为 403 ACCESS_DENIED，不暴露内部权限判断细节。
     */
    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<ErrorEnvelope> handlePermissionDenied(PermissionDeniedException e,
                                                                HttpServletRequest request) {
        return render(request, 403, "ACCESS_DENIED", "当前角色或权限不足。", false, List.of(), Map.of());
    }

    /**
     * 将请求体读取超限统一映射为 413 REQUEST_TOO_LARGE。
     */
    @ExceptionHandler(RequestBodyTooLargeException.class)
    public ResponseEntity<ErrorEnvelope> handleBodyTooLarge(RequestBodyTooLargeException e,
                                                            HttpServletRequest request) {
        return render(request, 413, "REQUEST_TOO_LARGE", "请求 body 超过允许上限。",
                false, List.of(), Map.of());
    }

    /**
     * 将请求体字段校验错误转换为 /body/ 明细并返回 422 VALIDATION_FAILED。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorEnvelope> handleValidation(MethodArgumentNotValidException e,
                                                          HttpServletRequest request) {
        List<FieldViolation> details = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new FieldViolation(
                        "/body/" + fieldError.getField(),
                        "INVALID",
                        fieldError.getDefaultMessage() == null ? "字段无效。" : fieldError.getDefaultMessage()))
                .toList();
        return render(request, 422, "VALIDATION_FAILED", "请求字段校验失败。", false, details, Map.of());
    }

    /**
     * 收集控制器方法参数约束错误，转换为 /param/ 明细并返回 422。
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorEnvelope> handleMethodValidation(HandlerMethodValidationException e,
                                                                HttpServletRequest request) {
        List<FieldViolation> details = new ArrayList<>();
        e.getParameterValidationResults().forEach(result -> result.getResolvableErrors()
                .forEach(error -> details.add(new FieldViolation(
                        "/param/" + result.getMethodParameter().getParameterName(),
                        "INVALID", error.getDefaultMessage()))));
        return render(request, 422, "VALIDATION_FAILED", "请求字段校验失败。",
                false, details.isEmpty() ? List.of() : details, Map.of());
    }

    /**
     * 转换约束违反结果，保留参数路径及可读提示并返回统一 422 响应。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorEnvelope> handleConstraintViolation(ConstraintViolationException e,
                                                                   HttpServletRequest request) {
        List<FieldViolation> details = e.getConstraintViolations().stream()
                .map(violation -> new FieldViolation(
                        "/param/" + violation.getPropertyPath(),
                        "INVALID",
                        violation.getMessage() == null ? "字段无效。" : violation.getMessage()))
                .toList();
        return render(request, 422, "VALIDATION_FAILED", "请求字段校验失败。",
                false, details.isEmpty() ? List.of() : details, Map.of());
    }

    /**
     * 区分请求体超限、字段类型错误、未知字段和 JSON 语法错误，按契约生成对应状态和字段明细。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorEnvelope> handleUnreadable(HttpMessageNotReadableException e,
                                                          HttpServletRequest request) {
        if (findCause(e, RequestBodyTooLargeException.class) != null) {
            return render(request, 413, "REQUEST_TOO_LARGE", "请求 body 超过允许上限。",
                    false, List.of(), Map.of());
        }
        Throwable invalidFormat = findCauseBySimpleName(e, "InvalidFormatException");
        if (invalidFormat != null) {
            boolean roleCode = invalidFormat.getMessage() != null
                    && invalidFormat.getMessage().contains("roleCode");
            String code = roleCode ? "ROLE_CODE_NOT_ALLOWED" : "VALIDATION_FAILED";
            String message = roleCode ? "roleCode 不属于批准的 P0 最小角色集合。" : "请求字段类型无效。";
            return render(request, 422, code, message, false, List.of(), Map.of());
        }
        String code = "MALFORMED_JSON";
        List<FieldViolation> details = List.of();
        String field = findUnrecognizedProperty(e);
        if (field != null) {
            code = "UNKNOWN_FIELD";
            details = List.of(new FieldViolation(
                    "/body/" + field, "UNKNOWN_FIELD", "请求包含未声明字段。"));
        }
        return render(request, 400, code, code.equals("UNKNOWN_FIELD") ? "请求包含未声明字段。" : "JSON 格式无效。",
                false, details, Map.of());
    }

    /**
     * 将不支持的 Content-Type 转为 415 MEDIA_TYPE_UNSUPPORTED。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorEnvelope> handleMediaType(HttpMediaTypeNotSupportedException e,
                                                         HttpServletRequest request) {
        return render(request, 415, "MEDIA_TYPE_UNSUPPORTED", "Content-Type 不符合声明。", false,
                List.of(), Map.of());
    }

    /**
     * 返回 405，并将已支持方法排序后写入 Allow 响应头。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorEnvelope> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                                  HttpServletRequest request) {
        String allow = e.getSupportedHttpMethods() == null ? ""
                : e.getSupportedHttpMethods().stream().map(HttpMethod::name)
                        .sorted().collect(java.util.stream.Collectors.joining(", "));
        return render(request, 405, "METHOD_NOT_ALLOWED", "HTTP 方法不允许。", false, List.of(),
                allow.isBlank() ? Map.of() : Map.of(HttpHeaders.ALLOW, allow));
    }

    /**
     * 将未知路径或资源缺失转换为 JSON 404，保持统一接口错误结构。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorEnvelope> handleNotFound(NoResourceFoundException e,
                                                        HttpServletRequest request) {
        return render(request, 404, "RESOURCE_NOT_FOUND", "路径或资源不存在。", false, List.of(), Map.of());
    }

    /**
     * 将路径参数转换失败映射为 422，并用 /path/ 明细定位参数。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorEnvelope> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                            HttpServletRequest request) {
        return render(request, 422, "VALIDATION_FAILED", "路径参数类型无效。", false,
                List.of(new FieldViolation("/path/" + e.getName(), "INVALID", "路径参数类型无效。")),
                Map.of());
    }

    /**
     * 对缺少的必需查询参数返回 400，并提供参数名。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorEnvelope> handleMissingParameter(MissingServletRequestParameterException e,
                                                                HttpServletRequest request) {
        return render(request, 400, "INVALID_QUERY_PARAMETER", "缺少必需 query 参数：" + e.getParameterName() + "。",
                false, List.of(), Map.of());
    }

    /**
     * 对缺少的必需请求头返回 422，保持字段校验契约一致。
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorEnvelope> handleMissingHeader(MissingRequestHeaderException e,
                                                             HttpServletRequest request) {
        return render(request, 422, "VALIDATION_FAILED", "缺少必需请求头：" + e.getHeaderName() + "。",
                false, List.of(), Map.of());
    }

    /**
     * 沿原因链定位未知字段异常并提取字段名，无法提取时使用稳定占位名称。
     */
    private static String findUnrecognizedProperty(HttpMessageNotReadableException e) {
        Throwable cause = findCauseBySimpleName(e, "UnrecognizedPropertyException");
        if (cause == null) {
            return null;
        }
        String message = cause.getMessage() == null ? "" : cause.getMessage();
        java.util.regex.Matcher matcher = UNRECOGNIZED_PROPERTY.matcher(message);
        return matcher.find() ? matcher.group(1) : "unknown";
    }

    /**
     * 按异常简单类名遍历原因链，以兼容不同 JSON 实现的异常类型。
     */
    private static Throwable findCauseBySimpleName(Throwable throwable, String simpleName) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (simpleName.equals(cause.getClass().getSimpleName())) {
                return cause;
            }
        }
        return null;
    }

    /**
     * 沿异常原因链查找指定类型，保留请求体超限等底层错误语义。
     */
    private static Throwable findCause(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return cause;
            }
        }
        return null;
    }

    /**
     * 补齐关联 UUID、JSON 类型和附加响应头，构造错误信封；没有字段明细时省略 details 内容。
     */
    private ResponseEntity<ErrorEnvelope> render(HttpServletRequest request,
                                                 int status,
                                                 String code,
                                                 String message,
                                                 boolean retryable,
                                                 List<FieldViolation> details,
                                                 java.util.Map<String, String> headers) {
        UUID correlationId = RequestCorrelation.currentOrNull(request);
        if (correlationId == null) {
            correlationId = UUID.randomUUID();
        }
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        httpHeaders.set("X-Correlation-Id", correlationId.toString());
        headers.forEach(httpHeaders::set);
        return new ResponseEntity<>(
                new ErrorEnvelope(new ApiError(code, message, correlationId, retryable,
                        details.isEmpty() ? null : details)),
                httpHeaders,
                HttpStatus.valueOf(status));
    }
}
