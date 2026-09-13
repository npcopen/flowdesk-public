package com.flowdesk.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/** Success envelope metadata (SCHEMA-001 meta). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiMeta(UUID correlationId, PageMeta page, List<AsyncProgress> asyncEffects) {

    public ApiMeta(UUID correlationId) {
        this(correlationId, null, null);
    }

    public ApiMeta(UUID correlationId, PageMeta page) {
        this(correlationId, page, null);
    }

    public ApiMeta(UUID correlationId, PageMeta page, List<AsyncProgress> asyncEffects) {
        this.correlationId = correlationId;
        this.page = page;
        this.asyncEffects = asyncEffects == null || asyncEffects.isEmpty() ? null : List.copyOf(asyncEffects);
    }
}
