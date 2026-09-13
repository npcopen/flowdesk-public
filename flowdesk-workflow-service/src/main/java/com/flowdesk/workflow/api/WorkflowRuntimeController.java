package com.flowdesk.workflow.api;

import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.PageMeta;
import com.flowdesk.common.web.RequestCorrelation;
import com.flowdesk.common.web.SuccessEnvelope;
import com.flowdesk.workflow.service.WorkflowPage;
import com.flowdesk.workflow.service.WorkflowRuntimeService;
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

/** HTTP contract for M6-B API-043..048. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkflowRuntimeController {

    private static final Set<String> INSTANCE_PARAMS = Set.of(
            "status", "ticketId", "workflowVersionId", "pageSize", "pageToken", "sort");
    private static final Set<String> TASK_PARAMS = Set.of(
            "status", "levelNo", "workflowInstanceId", "pageSize", "pageToken", "sort");

    private final WorkflowRuntimeService service;

    public WorkflowRuntimeController(WorkflowRuntimeService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/workflow-instances")
    public SuccessEnvelope<java.util.List<WorkflowDtos.WorkflowInstanceResponse>> instances(
            @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.single(params, "status", INSTANCE_PARAMS);
        WorkflowPage<WorkflowDtos.WorkflowInstanceResponse> page = service.listInstances(
                params.getFirst("status"), WorkflowQueryParams.uuid(params.getFirst("ticketId")),
                WorkflowQueryParams.uuid(params.getFirst("workflowVersionId")),
                WorkflowQueryParams.pageSize(params.getFirst("pageSize")),
                params.getFirst("pageToken"), params.getFirst("sort"));
        return page(page);
    }

    @GetMapping("/api/v1/workflow-instances/{workflowInstanceId}")
    public ResponseEntity<SuccessEnvelope<WorkflowDtos.WorkflowInstanceResponse>> instance(
            @PathVariable UUID workflowInstanceId,
            @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.none(params);
        WorkflowDtos.WorkflowInstanceResponse result = service.getInstance(workflowInstanceId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, WorkflowSupport.etag(result.lockVersion()))
                .body(success(result));
    }

    @GetMapping("/api/v1/workflow-instances/{workflowInstanceId}/approver-changes")
    public SuccessEnvelope<java.util.List<WorkflowDtos.ApproverChangeResponse>> approverChanges(
            @PathVariable UUID workflowInstanceId, @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.none(params);
        return success(service.approverChanges(workflowInstanceId));
    }

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

    @GetMapping("/api/v1/approval-tasks")
    public SuccessEnvelope<java.util.List<WorkflowDtos.ApprovalTaskResponse>> tasks(
            @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.single(params, "status", TASK_PARAMS);
        WorkflowPage<WorkflowDtos.ApprovalTaskResponse> page = service.listTasks(
                params.getFirst("status"),
                WorkflowQueryParams.positiveInteger(params.getFirst("levelNo")),
                WorkflowQueryParams.uuid(params.getFirst("workflowInstanceId")),
                WorkflowQueryParams.pageSize(params.getFirst("pageSize")),
                params.getFirst("pageToken"), params.getFirst("sort"));
        return page(page);
    }

    @GetMapping("/api/v1/approval-tasks/summary")
    public SuccessEnvelope<java.util.Map<String, Long>> taskSummary(
            @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.none(params);
        return success(service.taskSummary());
    }

    @GetMapping("/api/v1/approval-tasks/{approvalTaskId}")
    public ResponseEntity<SuccessEnvelope<WorkflowDtos.ApprovalTaskResponse>> task(
            @PathVariable UUID approvalTaskId,
            @RequestParam MultiValueMap<String, String> params) {
        WorkflowQueryParams.none(params);
        WorkflowDtos.ApprovalTaskResponse result = service.getTask(approvalTaskId);
        return taskResponse(result, false);
    }

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

    private static ResponseEntity<SuccessEnvelope<WorkflowDtos.ApprovalTaskResponse>> taskResponse(
            WorkflowDtos.ApprovalTaskResponse task, boolean replayed) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header(HttpHeaders.ETAG, WorkflowSupport.etag(task.lockVersion()));
        if (replayed) {
            response.header("Idempotency-Replayed", "true");
        }
        return response.body(success(task));
    }

    private static <T> SuccessEnvelope<T> success(T value) {
        return new SuccessEnvelope<>(value, new ApiMeta(RequestCorrelation.current()));
    }

    private static <T> SuccessEnvelope<java.util.List<T>> page(WorkflowPage<T> page) {
        return new SuccessEnvelope<>(page.items(), new ApiMeta(RequestCorrelation.current(),
                new PageMeta(page.items().size(), page.nextPageToken(), page.sort())));
    }
}
