package com.flowdesk.common.web;

import java.util.UUID;

/** API-SPEC SCHEMA-005 async effect progress marker. */
public record AsyncProgress(String kind, String state, UUID relatedRefId) {
}
