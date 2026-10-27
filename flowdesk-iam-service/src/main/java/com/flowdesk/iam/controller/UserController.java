package com.flowdesk.iam.controller;

import com.flowdesk.iam.dto.IamDtos;
import com.flowdesk.iam.dto.QueryParams;

import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.PageMeta;
import com.flowdesk.common.web.RequestCorrelation;
import com.flowdesk.common.web.SuccessEnvelope;
import com.flowdesk.iam.service.IamSupport;
import com.flowdesk.iam.service.PageResult;
import com.flowdesk.iam.service.UserManagementService;
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
import java.util.Set;
import java.util.UUID;

/**
 * 用户管理与最小身份查询入口，并提供工单处理人候选列表。
 */
@RestController
@RequestMapping("/api/v1")
public class UserController {

    private static final Set<String> LIST_PARAMS = Set.of(
            "status", "roleCode", "pageSize", "pageToken", "sort");
    private static final Set<String> CANDIDATE_PARAMS = Set.of("pageSize", "pageToken", "sort");

    private final UserManagementService service;

    /**
     * 注入当前 HTTP 入口所需的业务用例，使请求校验及响应包装调用同一服务能力。
     */
    public UserController(UserManagementService service) {
        this.service = service;
    }

    /**
     * 仅分页返回当前租户中启用且具备 Agent 角色的最小身份；查询作用域固定用于工单分派和转派。
     */
    @GetMapping("/users/ticket-assignee-candidates")
    public SuccessEnvelope<java.util.List<UserManagementService.Identity>> ticketAssigneeCandidates(
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.single(params, "sort", CANDIDATE_PARAMS);
        PageResult<UserManagementService.Identity> page = service.ticketAssigneeCandidates(
                QueryParams.pageSize(params.getFirst("pageSize")), params.getFirst("pageToken"),
                params.getFirst("sort"));
        return new SuccessEnvelope<>(page.items(), new ApiMeta(RequestCorrelation.current(),
                new PageMeta(page.items().size(), page.nextPageToken(), page.sort())));
    }

    /**
     * 接收并校验用户请求，委托业务用例分页查询当前租户的用户，返回统一成功响应及必要资源版本元数据。
     */
    @GetMapping("/users")
    public SuccessEnvelope<java.util.List<IamDtos.UserSummaryResponse>> listUsers(
            @RequestParam MultiValueMap<String, String> params) {
        PageResult<IamDtos.UserSummaryResponse> page = service.list(
                QueryParams.single(params, "status", LIST_PARAMS),
                QueryParams.single(params, "roleCode", LIST_PARAMS),
                QueryParams.pageSize(params.getFirst("pageSize")),
                params.getFirst("pageToken"),
                params.getFirst("sort"));
        return new SuccessEnvelope<>(page.items(), new ApiMeta(RequestCorrelation.current(),
                new PageMeta(page.items().size(), page.nextPageToken(), page.sort())));
    }

    /**
     * 接收并校验用户请求，委托业务用例创建当前租户的用户，返回统一成功响应及必要资源版本元数据。
     */
    @PostMapping("/users")
    public ResponseEntity<SuccessEnvelope<IamDtos.UserSummaryResponse>> createUser(
            @Valid @RequestBody IamDtos.UserCreateRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        UserManagementService.CreateUserResult result = service.create(request, idempotencyKey);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/users/{userId}")
                .buildAndExpand(result.user().userId()).toUri();
        return ResponseEntity.status(result.replayed() ? 200 : 201)
                .header("Idempotency-Replayed", result.replayed() ? "true" : null)
                .header(HttpHeaders.LOCATION, location.toString())
                .header(HttpHeaders.ETAG, "\"lv:" + result.user().lockVersion() + "\"")
                .body(new SuccessEnvelope<>(result.user(),
                        new ApiMeta(RequestCorrelation.current())));
    }

    /**
     * 校验身份读取权限后查询同租户最小用户资料，仅返回业务展示所需字段。
     */
    @GetMapping("/users/{userId}/identity")
    public SuccessEnvelope<UserManagementService.Identity> identity(@PathVariable UUID userId) {
        return new SuccessEnvelope<>(service.identity(userId), new ApiMeta(RequestCorrelation.current()));
    }

    /**
     * 接收并校验用户请求，委托业务用例读取单个当前租户的用户，返回统一成功响应及必要资源版本元数据。
     */
    @GetMapping("/users/{userId}")
    public ResponseEntity<SuccessEnvelope<IamDtos.UserSummaryResponse>> getUser(
            @PathVariable UUID userId,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        IamDtos.UserSummaryResponse user = service.get(userId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, "\"lv:" + user.lockVersion() + "\"")
                .body(new SuccessEnvelope<>(user, new ApiMeta(RequestCorrelation.current())));
    }

    /**
     * 接收并校验用户请求，委托业务用例按版本部分更新当前租户的用户，返回统一成功响应及必要资源版本元数据。
     */
    @PatchMapping("/users/{userId}")
    public ResponseEntity<SuccessEnvelope<IamDtos.UserSummaryResponse>> patchUser(
            @PathVariable UUID userId,
            @Valid @RequestBody IamDtos.UserPatchRequest request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        IamDtos.UserSummaryResponse user = service.patch(
                userId, request, IamSupport.parseIfMatch(ifMatch));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, "\"lv:" + user.lockVersion() + "\"")
                .body(new SuccessEnvelope<>(user, new ApiMeta(RequestCorrelation.current())));
    }
}
