package com.flowdesk.workflow.controller;

import com.flowdesk.workflow.dto.WorkflowDtos;
import com.flowdesk.workflow.dto.WorkflowQueryParams;

import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.PageMeta;
import com.flowdesk.common.web.RequestCorrelation;
import com.flowdesk.common.web.SuccessEnvelope;
import com.flowdesk.workflow.service.WorkflowPage;
import com.flowdesk.workflow.service.WorkflowRuntimeService;
import com.flowdesk.workflow.service.WorkflowRuntimeQueryService;
import com.flowdesk.workflow.service.WorkflowSupport;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

/**
 * 审批实例、任务、审批决定和审批人交接的 HTTP 入口。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkflowRuntimeController {

    private static final Set<String> INSTANCE_PARAMS = Set.of(
            "status", "ticketId", "workflowVersionId", "pageSize", "pageToken", "sort");
    private static final Set<String> TASK_PARAMS = Set.of(
            "status", "levelNo", "workflowInstanceId", "pageSize", "pageToken", "sort");

    private final WorkflowRuntimeService service;
    private final WorkflowRuntimeQueryService queries;

    /**
     * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
     */
    public WorkflowRuntimeController(WorkflowRuntimeService service,
                                     WorkflowRuntimeQueryService queries) {
        this.service = service;
        this.queries = queries;
    }

    /**
     * 解析工作流实例列表条件，再按当前调用者的读取权限返回分页结果。
     */
    @GetMapping("/api/v1/workflow-instances")
    public SuccessEnvelope<java.util.List<WorkflowDtos.WorkflowInstanceResponse>> instances(
            @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.single(params, "status", INSTANCE_PARAMS);
        WorkflowPage<WorkflowDtos.WorkflowInstanceResponse> page = queries.listInstances(
                params.getFirst("status"), WorkflowQueryParams.uuid(params.getFirst("ticketId")),
                WorkflowQueryParams.uuid(params.getFirst("workflowVersionId")),
                WorkflowQueryParams.pageSize(params.getFirst("pageSize")),
                params.getFirst("pageToken"), params.getFirst("sort"));
        return page(page);
    }

    /**
     * 读取可见实例详情，携带实例锁版本供后续条件操作使用。
     */
    @GetMapping("/api/v1/workflow-instances/{workflowInstanceId}")
    public ResponseEntity<SuccessEnvelope<WorkflowDtos.WorkflowInstanceResponse>> instance(
            @PathVariable UUID workflowInstanceId,
            @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.none(params);
        WorkflowDtos.WorkflowInstanceResponse result = queries.getInstance(workflowInstanceId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, WorkflowSupport.etag(result.lockVersion()))
                .body(success(result));
    }

    /**
     * 查询实例的审批交接历史，供授权用户追溯原办理人及变更原因。
     */
    @GetMapping("/api/v1/workflow-instances/{workflowInstanceId}/approver-changes")
    public SuccessEnvelope<java.util.List<WorkflowDtos.ApproverChangeResponse>> approverChanges(
            @PathVariable UUID workflowInstanceId, @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.none(params);
        return success(queries.approverChanges(workflowInstanceId));
    }

    /**
     * 接收审批人交接命令，经权限、版本与幂等保护后返回最新实例及版本。
     */
    @PostMapping("/api/v1/workflow-instances/{workflowInstanceId}/approver-changes")
    public ResponseEntity<SuccessEnvelope<WorkflowDtos.WorkflowInstanceResponse>> changeApprover(
            @PathVariable UUID workflowInstanceId, @Valid @RequestBody WorkflowDtos.ApproverChangeRequest request,
            @RequestHeader(name="If-Match", required=false) String ifMatch,
            @RequestHeader(name="Idempotency-Key", required=false) String idempotencyKey,
            @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.none(params);
        var result = service.changeApprover(workflowInstanceId, request, ifMatch, idempotencyKey);
        var response = ResponseEntity.ok().header(HttpHeaders.ETAG, WorkflowSupport.etag(result.instance().lockVersion()));
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        return response.body(success(result.instance()));
    }

    /**
     * 按固定审批人或租户读取范围查询任务，封装稳定分页边界。
     */
    @GetMapping("/api/v1/approval-tasks")
    public SuccessEnvelope<java.util.List<WorkflowDtos.ApprovalTaskResponse>> tasks(
            @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.single(params, "status", TASK_PARAMS);
        WorkflowPage<WorkflowDtos.ApprovalTaskResponse> page = queries.listTasks(
                params.getFirst("status"),
                WorkflowQueryParams.positiveInteger(params.getFirst("levelNo")),
                WorkflowQueryParams.uuid(params.getFirst("workflowInstanceId")),
                WorkflowQueryParams.pageSize(params.getFirst("pageSize")),
                params.getFirst("pageToken"), params.getFirst("sort"));
        return page(page);
    }

    /**
     * 统计当前可见范围内的待办审批任务，供工作台摘要展示。
     */
    @GetMapping("/api/v1/approval-tasks/summary")
    public SuccessEnvelope<java.util.Map<String, Long>> taskSummary(
            @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.none(params);
        return success(queries.taskSummary());
    }

    /**
     * 读取当前可见审批任务，返回任务快照及资源锁版本。
     */
    @GetMapping("/api/v1/approval-tasks/{approvalTaskId}")
    public ResponseEntity<SuccessEnvelope<WorkflowDtos.ApprovalTaskResponse>> task(
            @PathVariable UUID approvalTaskId,
            @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.none(params);
        WorkflowDtos.ApprovalTaskResponse result = queries.getTask(approvalTaskId);
        return taskResponse(result, false);
    }

    /**
     * 提交当前分配任务的通过决定，由业务服务推进审批并返回版本及幂等响应。
     */
    @PostMapping("/api/v1/approval-tasks/{approvalTaskId}/approve")
    public ResponseEntity<SuccessEnvelope<WorkflowDtos.ApprovalTaskResponse>> approve(
            @PathVariable UUID approvalTaskId,
            @Valid @RequestBody WorkflowDtos.ApprovalDecisionRequest request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.none(params);
        WorkflowRuntimeService.DecisionResult result = service.decide(
                approvalTaskId, request, "APPROVE", ifMatch, idempotencyKey);
        return taskResponse(result.task(), result.replayed());
    }

    /**
     * 提交当前分配任务的拒绝决定，原因、版本与幂等约束由业务服务核验。
     */
    @PostMapping("/api/v1/approval-tasks/{approvalTaskId}/reject")
    public ResponseEntity<SuccessEnvelope<WorkflowDtos.ApprovalTaskResponse>> reject(
            @PathVariable UUID approvalTaskId,
            @Valid @RequestBody WorkflowDtos.ApprovalDecisionRequest request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.none(params);
        WorkflowRuntimeService.DecisionResult result = service.decide(
                approvalTaskId, request, "REJECT", ifMatch, idempotencyKey);
        return taskResponse(result.task(), result.replayed());
    }

    /**
     * 将审批任务转换为响应，HTTP 入口同时按任务版本设置 ETag。
     */
    private static ResponseEntity<SuccessEnvelope<WorkflowDtos.ApprovalTaskResponse>> taskResponse(
            WorkflowDtos.ApprovalTaskResponse task, boolean replayed) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header(HttpHeaders.ETAG, WorkflowSupport.etag(task.lockVersion()));
        if (replayed) {
            response.header("Idempotency-Replayed", "true");
        }
        return response.body(success(task));
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
}
