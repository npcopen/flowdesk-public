package com.flowdesk.ticket.api;

import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.PageMeta;
import com.flowdesk.common.web.RequestCorrelation;
import com.flowdesk.common.web.SuccessEnvelope;
import com.flowdesk.ticket.service.PageResult;
import com.flowdesk.ticket.service.SlaPolicyService;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** API-020 SLA policy reads. */
@RestController
@RequestMapping("/api/v1/sla-policies")
public class SlaPolicyController {

    private static final Set<String> LIST_PARAMS = Set.of("status", "pageSize", "pageToken", "sort");

    private final SlaPolicyService service;

    public SlaPolicyController(SlaPolicyService service) {
        this.service = service;
    }

    @GetMapping
    public SuccessEnvelope<java.util.List<TicketDtos.SlaPolicySummaryResponse>> list(
            @RequestParam MultiValueMap<String, String> params) {
        PageResult<TicketDtos.SlaPolicySummaryResponse> page = service.list(
                QueryParams.single(params, "status", LIST_PARAMS),
                QueryParams.pageSize(params.getFirst("pageSize")),
                params.getFirst("pageToken"),
                params.getFirst("sort"));
        return new SuccessEnvelope<>(page.items(), new ApiMeta(RequestCorrelation.current(),
                new PageMeta(page.items().size(), page.nextPageToken(), page.sort())));
    }
}
