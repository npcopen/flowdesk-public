package com.flowdesk.iam.api;

import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.PageMeta;
import com.flowdesk.common.web.RequestCorrelation;
import com.flowdesk.common.web.SuccessEnvelope;
import com.flowdesk.iam.service.BindingService;
import com.flowdesk.iam.service.PageResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.MultiValueMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * API-014/015 user-role bindings.
 */
@RestController
@RequestMapping("/api/v1")
public class UserRoleController {

    private static final Set<String> PARAMS = Set.of("state", "pageSize", "pageToken", "sort");

    private final BindingService service;

    public UserRoleController(BindingService service) {
        this.service = service;
    }

    @GetMapping("/users/{userId}/roles")
    public SuccessEnvelope<List<IamDtos.AccessBindingResponse>> listUserRoles(
            @PathVariable UUID userId, @RequestParam MultiValueMap<String, String> params) {
        QueryParams.single(params, "state", PARAMS);
        PageResult<IamDtos.AccessBindingResponse> page = service.listUserRoles(
                userId, params.getFirst("state"), QueryParams.pageSize(params.getFirst("pageSize")),
                params.getFirst("pageToken"), params.getFirst("sort"));
        return new SuccessEnvelope<>(page.items(), new ApiMeta(RequestCorrelation.current(),
                new PageMeta(page.items().size(), page.nextPageToken(), page.sort())));
    }

    @PutMapping("/users/{userId}/roles/{roleId}")
    public ResponseEntity<SuccessEnvelope<IamDtos.AccessBindingResponse>> putUserRole(
            @PathVariable UUID userId,
            @PathVariable UUID roleId,
            @Valid @RequestBody IamDtos.BindingWriteRequest request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        BindingService.PutBindingResult result = service.putUserRole(
                userId, roleId, request, ifMatch, ifNoneMatch, idempotencyKey);
        return ResponseEntity.status(result.created() ? 201 : 200)
                .header("Idempotency-Replayed", result.replayed() ? "true" : null)
                .header(HttpHeaders.ETAG, "\"lv:" + result.binding().lockVersion() + "\"")
                .body(new SuccessEnvelope<>(result.binding(),
                        new ApiMeta(RequestCorrelation.current())));
    }
}
