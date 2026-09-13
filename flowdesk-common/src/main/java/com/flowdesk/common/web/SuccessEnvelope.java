package com.flowdesk.common.web;

/** API-SPEC SCHEMA-001 success envelope. */
public record SuccessEnvelope<T>(T data, ApiMeta meta) {
}
