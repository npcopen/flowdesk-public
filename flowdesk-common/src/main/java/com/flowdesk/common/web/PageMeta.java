package com.flowdesk.common.web;

/**
 * 游标分页的响应元数据，对应 API-SPEC SCHEMA-003。
 */
public record PageMeta(int pageSize, String nextPageToken, String sort) {
}
