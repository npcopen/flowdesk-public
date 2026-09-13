package com.flowdesk.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/** API-SPEC SCHEMA-002 error object. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code,
                       String message,
                       UUID correlationId,
                       boolean retryable,
                       List<FieldViolation> details) {
}
