package com.flowdesk.common.web;

/**
 * 统一错误响应的外层结构，对应 API-SPEC SCHEMA-002。
 */
public record ErrorEnvelope(ApiError error) {
}
