package com.flowdesk.workflow.controller;

import com.flowdesk.workflow.dto.WorkflowDtos;
import com.flowdesk.workflow.dto.WorkflowQueryParams;

import com.flowdesk.common.web.ApiException;
import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.PageMeta;
import com.flowdesk.common.web.RequestCorrelation;
import com.flowdesk.common.web.SuccessEnvelope;
import com.flowdesk.workflow.service.WorkflowDefinitionService;
import com.flowdesk.workflow.service.WorkflowPage;
import com.flowdesk.workflow.service.WorkflowSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

/**
 * 流程定义、草稿、校验与发布的 HTTP 入口（API-034～042/062）。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkflowDefinitionController {

    private static final Set<String> DEFINITION_LIST_PARAMS =
            Set.of("pageSize", "pageToken", "sort");
    private static final Set<String> VERSION_LIST_PARAMS =
            Set.of("versionNo", "pageSize", "pageToken", "sort");

    private final WorkflowDefinitionService service;

    /**
     * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
     */
    public WorkflowDefinitionController(WorkflowDefinitionService service) {
        this.service = service;
    }

    /**
     * 解析流程定义列表参数，调用只读用例并封装分页元数据。
     */
    @GetMapping("/api/v1/workflow-definitions")
    public SuccessEnvelope<java.util.List<WorkflowDtos.WorkflowDefinitionResponse>> definitions(
            @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.single(params, "pageSize", DEFINITION_LIST_PARAMS);
        int pageSize = WorkflowQueryParams.pageSize(params.getFirst("pageSize"));
        WorkflowPage<WorkflowDtos.WorkflowDefinitionResponse> page = service.listDefinitions(
                pageSize, params.getFirst("pageToken"), params.getFirst("sort"));
        return page(page);
    }

    /**
     * 读取流程定义详情，在响应中携带当前版本 ETag。
     */
    @GetMapping("/api/v1/workflow-definitions/{definitionId}")
    public ResponseEntity<SuccessEnvelope<WorkflowDtos.WorkflowDefinitionResponse>> definition(
            @PathVariable UUID definitionId,
            @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.none(params);
        WorkflowDtos.WorkflowDefinitionResponse result = service.getDefinition(definitionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, WorkflowSupport.etag(result.lockVersion()))
                .body(success(result));
    }

    /**
     * 基于定义创建受版本与幂等保护的草稿，重复请求复用原草稿结果。
     */
    @PostMapping("/api/v1/workflow-definitions/{definitionId}/drafts")
    public ResponseEntity<SuccessEnvelope<WorkflowDtos.WorkflowDraftResponse>> createDraft(
            @PathVariable UUID definitionId,
            @Valid @RequestBody WorkflowDtos.WorkflowDraftWriteRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.none(params);
        WorkflowDefinitionService.DraftCommandResult result = service.createDraft(
                definitionId, request, idempotencyKey, ifMatch);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/workflow-drafts/{draftId}")
                .buildAndExpand(result.draft().draftId()).toUri();
        ResponseEntity.BodyBuilder response = ResponseEntity
                .status(result.replayed() ? 200 : 201)
                .header(HttpHeaders.LOCATION, location.toString())
                .header(HttpHeaders.ETAG, WorkflowSupport.etag(result.draft().lockVersion()));
        if (result.replayed()) {
            response.header("Idempotency-Replayed", "true");
        }
        return response.body(success(result.draft()));
    }

    /**
     * 读取指定定义的当前可见草稿，并返回对应资源版本信息。
     */
    @GetMapping("/api/v1/workflow-definitions/{definitionId}/drafts/current")
    public ResponseEntity<SuccessEnvelope<WorkflowDtos.WorkflowDraftResponse>> currentDraft(
            @PathVariable UUID definitionId,
            @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.none(params);
        WorkflowDtos.WorkflowDraftResponse result = service.getCurrentDraft(definitionId);
        return draftResponse(result);
    }

    /**
     * 读取指定草稿详情，统一返回内容与当前版本 ETag。
     */
    @GetMapping("/api/v1/workflow-drafts/{draftId}")
    public ResponseEntity<SuccessEnvelope<WorkflowDtos.WorkflowDraftResponse>> draft(
            @PathVariable UUID draftId,
            @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.none(params);
        return draftResponse(service.getDraft(draftId));
    }

    /**
     * 按客户端版本保存草稿修改，编辑后原规则校验结果不再作为发布依据。
     */
    @PatchMapping("/api/v1/workflow-drafts/{draftId}")
    public ResponseEntity<SuccessEnvelope<WorkflowDtos.WorkflowDraftResponse>> updateDraft(
            @PathVariable UUID draftId,
            @Valid @RequestBody WorkflowDtos.WorkflowDraftWriteRequest request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.none(params);
        return draftResponse(service.updateDraft(draftId, request, ifMatch));
    }

    /**
     * 校验指定版本草稿的受限规则和审批配置，返回与本次草稿内容对应的校验结果。
     */
    @PostMapping("/api/v1/workflow-drafts/{draftId}/validate")
    public ResponseEntity<SuccessEnvelope<WorkflowDtos.WorkflowValidationResponse>> validateDraft(
            @PathVariable UUID draftId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam MultiValueMap<String, String> params,
            HttpServletRequest servletRequest) {
        WorkflowQueryParams.none(params);
        rejectBody(servletRequest);
        WorkflowDtos.WorkflowValidationResponse result = service.validateDraft(draftId, ifMatch);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, WorkflowSupport.etag(result.lockVersion()))
                .body(success(result));
    }

    /**
     * 发布已校验草稿为不可变版本，使用幂等键避免重复请求创建多个版本。
     */
    @PostMapping("/api/v1/workflow-drafts/{draftId}/publish")
    public ResponseEntity<SuccessEnvelope<WorkflowDtos.WorkflowVersionResponse>> publishDraft(
            @PathVariable UUID draftId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam MultiValueMap<String, String> params,
            HttpServletRequest servletRequest) {
        WorkflowQueryParams.none(params);
        rejectBody(servletRequest);
        WorkflowDefinitionService.VersionCommandResult result = service.publishDraft(
                draftId, ifMatch, idempotencyKey);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/workflow-versions/{workflowVersionId}")
                .buildAndExpand(result.version().workflowVersionId()).toUri();
        ResponseEntity.BodyBuilder response = ResponseEntity
                .status(result.replayed() ? 200 : 201)
                .header(HttpHeaders.LOCATION, location.toString());
        if (result.replayed()) {
            response.header("Idempotency-Replayed", "true");
        }
        return response.body(success(result.version()));
    }

    /**
     * 按定义及可选版本号查询已发布版本，封装游标分页元数据。
     */
    @GetMapping("/api/v1/workflow-definitions/{definitionId}/versions")
    public SuccessEnvelope<java.util.List<WorkflowDtos.WorkflowVersionResponse>> versions(
            @PathVariable UUID definitionId,
            @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.single(params, "versionNo", VERSION_LIST_PARAMS);
        WorkflowPage<WorkflowDtos.WorkflowVersionResponse> page = service.listVersions(
                definitionId, WorkflowQueryParams.positiveInteger(params.getFirst("versionNo")),
                WorkflowQueryParams.pageSize(params.getFirst("pageSize")),
                params.getFirst("pageToken"), params.getFirst("sort"));
        return page(page);
    }

    /**
     * 读取指定已发布版本的不可变规则快照，供客户端查看和引用。
     */
    @GetMapping("/api/v1/workflow-versions/{workflowVersionId}")
    public SuccessEnvelope<WorkflowDtos.WorkflowVersionResponse> version(
            @PathVariable UUID workflowVersionId,
            @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.none(params);
        return success(service.getVersion(workflowVersionId));
    }

    /**
     * 转换草稿数据并按接口场景携带版本信息，供条件更新继续使用。
     */
    private static ResponseEntity<SuccessEnvelope<WorkflowDtos.WorkflowDraftResponse>> draftResponse(
            WorkflowDtos.WorkflowDraftResponse result) {
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, WorkflowSupport.etag(result.lockVersion()))
                .body(success(result));
    }

    /**
     * 封装业务成功结果并关联当前请求的链路标识。
     */
    private static <T> SuccessEnvelope<T> success(T value) {
        return new SuccessEnvelope<>(value, new ApiMeta(RequestCorrelation.current()));
    }

    /**
     * 封装分页数据、下一页游标和实际排序，保持客户端翻页协议一致。
     */
    private static <T> SuccessEnvelope<java.util.List<T>> page(WorkflowPage<T> page) {
        return new SuccessEnvelope<>(page.items(), new ApiMeta(RequestCorrelation.current(),
                new PageMeta(page.items().size(), page.nextPageToken(), page.sort())));
    }

    /**
     * 拒绝不允许请求体的命令输入，避免多余业务内容被静默忽略。
     */
    private static void rejectBody(HttpServletRequest request) {
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
            throw ApiException.badRequest("REQUEST_BODY_NOT_ALLOWED", "该命令不接受请求 body。");
        }
    }
}
