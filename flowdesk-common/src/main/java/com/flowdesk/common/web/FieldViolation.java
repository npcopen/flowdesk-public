package com.flowdesk.common.web;

/**
 * 字段校验失败的明细，对应 API-SPEC SCHEMA-004。
 */
public record FieldViolation(String field, String reason, String message) {
}
