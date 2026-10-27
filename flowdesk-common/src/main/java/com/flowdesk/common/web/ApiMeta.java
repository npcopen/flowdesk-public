package com.flowdesk.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * 成功响应的元数据，承载关联标识、分页和异步处理进度等信息。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiMeta(UUID correlationId, PageMeta page, List<AsyncProgress> asyncEffects) {

    /**
     * 组装关联 UUID、可选分页及异步效果元数据；空异步效果规范为空，非空集合复制后保存。
     */
    public ApiMeta(UUID correlationId) {
        this(correlationId, null, null);
    }

    /**
     * 组装关联 UUID、可选分页及异步效果元数据；空异步效果规范为空，非空集合复制后保存。
     */
    public ApiMeta(UUID correlationId, PageMeta page) {
        this(correlationId, page, null);
    }

    /**
     * 组装关联 UUID、可选分页及异步效果元数据；空异步效果规范为空，非空集合复制后保存。
     */
    public ApiMeta(UUID correlationId, PageMeta page, List<AsyncProgress> asyncEffects) {
        this.correlationId = correlationId;
        this.page = page;
        this.asyncEffects = asyncEffects == null || asyncEffects.isEmpty() ? null : List.copyOf(asyncEffects);
    }
}
