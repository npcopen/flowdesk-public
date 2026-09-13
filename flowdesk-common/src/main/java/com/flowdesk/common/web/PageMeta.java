package com.flowdesk.common.web;

/** API-SPEC SCHEMA-003 page metadata. */
public record PageMeta(int pageSize, String nextPageToken, String sort) {
}
