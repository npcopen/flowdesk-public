package com.flowdesk.common.web;

/** API-SPEC SCHEMA-004 field violation. */
public record FieldViolation(String field, String reason, String message) {
}
