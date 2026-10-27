package com.flowdesk.common.web;

import java.util.UUID;

/**
 * 异步副作用的处理进度标记，对应 API-SPEC SCHEMA-005。
 */
public record AsyncProgress(String kind, String state, UUID relatedRefId) {
}
