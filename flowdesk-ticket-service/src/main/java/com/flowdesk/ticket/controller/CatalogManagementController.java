package com.flowdesk.ticket.controller;

import com.flowdesk.ticket.dto.CatalogWriteRequest;
import com.flowdesk.ticket.catalog.*;

import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.RequestCorrelation;
import com.flowdesk.common.web.SuccessEnvelope;
import com.flowdesk.ticket.dto.TicketDtos;
import com.flowdesk.ticket.service.TicketSupport;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 服务目录完整配置的管理入口，将创建幂等标识和更新版本交由业务服务校验。 */
@RestController
@RequestMapping("/api/v1/service-catalog-items")
public class CatalogManagementController {

    private final CatalogManagementService service;

    /**
     * 构造工单组件并保存调用依赖。
     */
    public CatalogManagementController(CatalogManagementService service) {
        this.service = service;
    }

    /**
     * 校验工单创建请求并保存事实，按接口约定处理幂等及版本条件。
     */
    @PostMapping
    public ResponseEntity<SuccessEnvelope<TicketDtos.ServiceCatalogItemResponse>> create(
            @Valid @RequestBody CatalogWriteRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader("Authorization") String authorization) {
        var result = service.create(request, key, authorization);
        return ResponseEntity.status(result.replayed() ? 200 : 201)
                .header("ETag", "\"lv:" + result.item().lockVersion() + "\"")
                .header("Location", "/api/v1/service-catalog-items/" + result.item().catalogItemId())
                .body(new SuccessEnvelope<>(result.item(), new ApiMeta(RequestCorrelation.current())));
    }

    /**
     * 校验目录风险、工作流版本与 SLA 绑定，按预期版本更新仅影响未来工单的配置。
     */
    @PutMapping("/{id}/configuration")
    public ResponseEntity<SuccessEnvelope<TicketDtos.ServiceCatalogItemResponse>> configure(
            @PathVariable UUID id,
            @Valid @RequestBody CatalogWriteRequest request,
            @RequestHeader(value = "If-Match", required = false) String match,
            @RequestHeader("Authorization") String authorization) {
        var item = service.configure(id, request, TicketSupport.parseIfMatch(match), authorization);
        return ResponseEntity.ok()
                .header("ETag", "\"lv:" + item.lockVersion() + "\"")
                .body(new SuccessEnvelope<>(item, new ApiMeta(RequestCorrelation.current())));
    }
}
