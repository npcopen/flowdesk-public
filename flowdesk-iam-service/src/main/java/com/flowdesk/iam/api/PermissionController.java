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
 * API-011..013 permission catalog and role-permission bindings.
 */
@RestController
@RequestMapping("/api/v1")
public class PermissionController {

    private static final Set<String> PERMISSION_PARAMS = Set.of("pageSize", "pageToken", "sort");
    private static final Set<String> ROLE_PERMISSION_PARAMS = Set.of("state", "pageSize", "pageToken", "sort");

    private final BindingService service;

    public PermissionController(BindingService service) {
        this.service = service;
    }

    @GetMapping("/permissions")
    public SuccessEnvelope<List<IamDtos.PermissionResponse>> listPermissions(
            @RequestParam MultiValueMap<String, String> params) {
        PageResult<IamDtos.PermissionResponse> page = service.listPermissions(
                QueryParams.pageSize(params.getFirst("pageSize")),
                params.getFirst("pageToken"),
                QueryParams.single(params, "sort", PERMISSION_PARAMS));
        return new SuccessEnvelope<>(page.items(), new ApiMeta(RequestCorrelation.current(),
                new PageMeta(page.items().size(), page.nextPageToken(), page.sort())));
    }

    @GetMapping("/roles/{roleId}/permissions")
    public SuccessEnvelope<List<IamDtos.AccessBindingResponse>> listRolePermissions(
            @PathVariable UUID roleId, @RequestParam MultiValueMap<String, String> params) {
        QueryParams.single(params, "state", ROLE_PERMISSION_PARAMS);
        PageResult<IamDtos.AccessBindingResponse> page = service.listRolePermissions(
                roleId, params.getFirst("state"), QueryParams.pageSize(params.getFirst("pageSize")),
                params.getFirst("pageToken"), params.getFirst("sort"));
        return new SuccessEnvelope<>(page.items(), new ApiMeta(RequestCorrelation.current(),
                new PageMeta(page.items().size(), page.nextPageToken(), page.sort())));
    }

    @PutMapping("/roles/{roleId}/permissions/{permissionId}")
    public ResponseEntity<SuccessEnvelope<IamDtos.AccessBindingResponse>> putRolePermission(
            @PathVariable UUID roleId,
            @PathVariable UUID permissionId,
            @Valid @RequestBody IamDtos.BindingWriteRequest request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        BindingService.PutBindingResult result = service.putRolePermission(
                roleId, permissionId, request, ifMatch, ifNoneMatch, idempotencyKey);
        return ResponseEntity.status(result.created() ? 201 : 200)
                .header("Idempotency-Replayed", result.replayed() ? "true" : null)
                .header(HttpHeaders.ETAG, "\"lv:" + result.binding().lockVersion() + "\"")
                .body(new SuccessEnvelope<>(result.binding(),
                        new ApiMeta(RequestCorrelation.current())));
    }
}
