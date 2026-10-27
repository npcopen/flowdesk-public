package com.flowdesk.iam.controller;

import com.flowdesk.iam.dto.IamDtos;
import com.flowdesk.iam.dto.QueryParams;

import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.PageMeta;
import com.flowdesk.common.web.RequestCorrelation;
import com.flowdesk.common.web.SuccessEnvelope;
import com.flowdesk.iam.service.IamSupport;
import com.flowdesk.iam.service.PageResult;
import com.flowdesk.iam.service.RoleManagementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 租户内角色的列表、详情、创建和更新接口（API-007～010）。
 */
@RestController
@RequestMapping("/api/v1")
public class RoleController {

    private static final Set<String> LIST_PARAMS = Set.of("status", "pageSize", "pageToken", "sort");

    private final RoleManagementService service;

    /**
     * 注入当前 HTTP 入口所需的业务用例，使请求校验及响应包装调用同一服务能力。
     */
    public RoleController(RoleManagementService service) {
        this.service = service;
    }

    /**
     * 接收并校验角色请求，委托业务用例分页查询当前租户的角色，返回统一成功响应及必要资源版本元数据。
     */
    @GetMapping("/roles")
    public SuccessEnvelope<List<IamDtos.RoleResponse>> listRoles(
            @RequestParam MultiValueMap<String, String> params) {
        PageResult<IamDtos.RoleResponse> page = service.list(
                QueryParams.single(params, "status", LIST_PARAMS),
                QueryParams.pageSize(params.getFirst("pageSize")),
                params.getFirst("pageToken"),
                params.getFirst("sort"));
        return new SuccessEnvelope<>(page.items(), new ApiMeta(RequestCorrelation.current(),
                new PageMeta(page.items().size(), page.nextPageToken(), page.sort())));
    }

    /**
     * 接收并校验角色请求，委托业务用例创建当前租户的角色，返回统一成功响应及必要资源版本元数据。
     */
    @PostMapping("/roles")
    public ResponseEntity<SuccessEnvelope<IamDtos.RoleResponse>> createRole(
            @Valid @RequestBody IamDtos.RoleCreateRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        RoleManagementService.CreateRoleResult result = service.create(request, idempotencyKey);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/roles/{roleId}")
                .buildAndExpand(result.role().roleId()).toUri();
        return ResponseEntity.status(result.replayed() ? 200 : 201)
                .header("Idempotency-Replayed", result.replayed() ? "true" : null)
                .header(HttpHeaders.LOCATION, location.toString())
                .header(HttpHeaders.ETAG, "\"lv:" + result.role().lockVersion() + "\"")
                .body(new SuccessEnvelope<>(result.role(),
                        new ApiMeta(RequestCorrelation.current())));
    }

    /**
     * 接收并校验角色请求，委托业务用例读取单个当前租户的角色，返回统一成功响应及必要资源版本元数据。
     */
    @GetMapping("/roles/{roleId}")
    public ResponseEntity<SuccessEnvelope<IamDtos.RoleResponse>> getRole(
            @PathVariable UUID roleId,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        IamDtos.RoleResponse role = service.get(roleId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, "\"lv:" + role.lockVersion() + "\"")
                .body(new SuccessEnvelope<>(role, new ApiMeta(RequestCorrelation.current())));
    }

    /**
     * 接收并校验角色请求，委托业务用例按版本部分更新当前租户的角色，返回统一成功响应及必要资源版本元数据。
     */
    @PatchMapping("/roles/{roleId}")
    public ResponseEntity<SuccessEnvelope<IamDtos.RoleResponse>> patchRole(
            @PathVariable UUID roleId,
            @Valid @RequestBody IamDtos.RolePatchRequest request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        IamDtos.RoleResponse role = service.patch(
                roleId, request, IamSupport.parseIfMatch(ifMatch));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, "\"lv:" + role.lockVersion() + "\"")
                .body(new SuccessEnvelope<>(role, new ApiMeta(RequestCorrelation.current())));
    }
}
