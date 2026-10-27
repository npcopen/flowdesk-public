package com.flowdesk.common.web;

/**
 * 统一成功响应的外层结构，对应 API-SPEC SCHEMA-001。
 */
public record SuccessEnvelope<T>(T data, ApiMeta meta) {
}
