package com.flowdesk.ticket.controller;

import com.flowdesk.ticket.catalog.*;

import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.PageMeta;
import com.flowdesk.common.web.RequestCorrelation;
import com.flowdesk.common.web.SuccessEnvelope;
import com.flowdesk.ticket.dto.QueryParams;
import com.flowdesk.ticket.dto.TicketDtos;
import com.flowdesk.ticket.service.PageResult;
import com.flowdesk.ticket.service.TicketSupport;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

/** API-017/018/019：服务目录列表、详情及最小字段维护的 HTTP 入口。 */
@RestController
@RequestMapping("/api/v1/service-catalog-items")
public class CatalogController {

    private static final Set<String> LIST_PARAMS = Set.of(
            "status", "riskLevel", "pageSize", "pageToken", "sort");

    private final CatalogService service;

    /**
     * 构造工单组件并保存调用依赖。
     */
    public CatalogController(CatalogService service) {
        this.service = service;
    }

    /**
     * 按筛选条件与稳定游标分页查询工单数据，返回结果及后续页边界。
     */
    @GetMapping
    public SuccessEnvelope<java.util.List<TicketDtos.ServiceCatalogItemResponse>> list(
            @RequestParam MultiValueMap<String, String> params) {
        PageResult<TicketDtos.ServiceCatalogItemResponse> page = service.list(
                QueryParams.single(params, "status", LIST_PARAMS),
                QueryParams.single(params, "riskLevel", LIST_PARAMS),
                QueryParams.pageSize(params.getFirst("pageSize")),
                params.getFirst("pageToken"),
                params.getFirst("sort"));
        return new SuccessEnvelope<>(page.items(), new ApiMeta(RequestCorrelation.current(),
                new PageMeta(page.items().size(), page.nextPageToken(), page.sort())));
    }

    /**
     * 读取指定工单详情，并依据当前租户与权限校验资源可见性。
     */
    @GetMapping("/{catalogItemId}")
    public ResponseEntity<SuccessEnvelope<TicketDtos.ServiceCatalogItemResponse>> get(
            @PathVariable UUID catalogItemId,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        TicketDtos.ServiceCatalogItemResponse item = service.get(catalogItemId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, "\"lv:" + item.lockVersion() + "\"")
                .body(new SuccessEnvelope<>(item, new ApiMeta(RequestCorrelation.current())));
    }

    /**
     * 执行目录局部更新，区分字段缺省和显式值，并以 If-Match 防止覆盖并发修改。
     */
    @PatchMapping("/{catalogItemId}")
    public ResponseEntity<SuccessEnvelope<TicketDtos.ServiceCatalogItemResponse>> patch(
            @PathVariable UUID catalogItemId,
            @Valid @RequestBody TicketDtos.ServiceCatalogPatchRequest request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        TicketDtos.ServiceCatalogItemResponse item = service.patch(
                catalogItemId, request, TicketSupport.parseIfMatch(ifMatch));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, "\"lv:" + item.lockVersion() + "\"")
                .body(new SuccessEnvelope<>(item, new ApiMeta(RequestCorrelation.current())));
    }
}
