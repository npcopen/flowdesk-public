package com.flowdesk.ticket.api;

import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.PageMeta;
import com.flowdesk.common.web.RequestCorrelation;
import com.flowdesk.common.web.SuccessEnvelope;
import com.flowdesk.ticket.service.CatalogService;
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

/** API-017/018/019 service catalog reads and minimal maintenance. */
@RestController
@RequestMapping("/api/v1/service-catalog-items")
public class CatalogController {

    private static final Set<String> LIST_PARAMS = Set.of(
            "status", "riskLevel", "pageSize", "pageToken", "sort");

    private final CatalogService service;

    public CatalogController(CatalogService service) {
        this.service = service;
    }

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
