package com.flowdesk.iam.controller;

import com.flowdesk.iam.dto.IamDtos;
import com.flowdesk.iam.dto.QueryParams;

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
 * 权限目录及角色权限关系的查询、写入入口（API-011～013）。
 */
@RestController
@RequestMapping("/api/v1")
public class PermissionController {

    private static final Set<String> PERMISSION_PARAMS = Set.of("pageSize", "pageToken", "sort");
    private static final Set<String> ROLE_PERMISSION_PARAMS = Set.of("state", "pageSize", "pageToken", "sort");

    private final BindingService service;

    /**
     * 注入当前 HTTP 入口所需的业务用例，使请求校验及响应包装调用同一服务能力。
     */
    public PermissionController(BindingService service) {
        this.service = service;
    }

    /**
     * 校验权限目录读取权限，以白名单排序和签名游标查询当前租户的权限列表。
     */
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

    /**
     * 确认角色属于当前租户，按绑定状态筛选并按权限编码分页返回角色的权限关系。
     */
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

    /**
     * 校验权限上限、写入前提和幂等意图后授予或撤销角色权限；保留组织管理员的基础 IAM 能力。
     */
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
