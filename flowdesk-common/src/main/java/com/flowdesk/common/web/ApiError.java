package com.flowdesk.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * 统一错误响应中的错误对象，对应 API-SPEC SCHEMA-002。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code,
                       String message,
                       UUID correlationId,
                       boolean retryable,
                       List<FieldViolation> details) {
}
