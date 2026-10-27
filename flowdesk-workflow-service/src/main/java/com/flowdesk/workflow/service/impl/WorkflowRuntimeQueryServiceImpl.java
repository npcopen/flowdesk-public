package com.flowdesk.workflow.service.impl;

import com.flowdesk.workflow.model.persistence.*;
import com.flowdesk.workflow.service.*;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.workflow.dto.WorkflowDtos;
import com.flowdesk.workflow.mapper.WorkflowRuntimeMapper;
import com.flowdesk.workflow.model.persistence.InstanceRow;
import com.flowdesk.workflow.model.persistence.TaskRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.flowdesk.workflow.service.WorkflowRuntimeResponseMapper.instanceResponse;
import static com.flowdesk.workflow.service.WorkflowRuntimeResponseMapper.taskResponse;

/**
 * 审批运行时的只读查询；区分租户级与本人任务可见范围，并提供不透明游标分页。
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkflowRuntimeQueryServiceImpl implements WorkflowRuntimeQueryService {
    private static final Set<String> INSTANCE_STATUSES =
            Set.of("RUNNING", "COMPLETED_PASSED", "COMPLETED_REJECTED");
    private static final Set<String> TASK_STATUSES =
            Set.of("PENDING", "APPROVED", "REJECTED");
    private static final Set<String> INSTANCE_SORTS = Set.of("-startedAt", "startedAt");
    private static final Set<String> TASK_SORTS =
            Set.of("-createdAt", "createdAt", "-decidedAt");

    private final WorkflowRuntimeMapper repository;
    private final WorkflowCursorTokenService cursorTokens;

    /**
     * 注入实例及任务查询映射和签名游标服务，供固定审批人范围与租户级读取使用一致分页。
     */
    public WorkflowRuntimeQueryServiceImpl(WorkflowRuntimeMapper repository,
                                       WorkflowCursorTokenService cursorTokens) {
        this.repository = repository;
        this.cursorTokens = cursorTokens;
    }

    /**
     * 分页查询当前租户可见的工作流实例，按排序及游标生成稳定结果。
     */
    public WorkflowPage<WorkflowDtos.WorkflowInstanceResponse> listInstances(
            String status, UUID ticketId, UUID workflowVersionId, int pageSize,
            String pageToken, String sort) {
        Scope scope = readScope();
        if (status != null && !INSTANCE_STATUSES.contains(status)) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "Workflow Instance 状态无效。");
        }
        String normalizedSort = normalize(sort, "-startedAt", INSTANCE_SORTS);
        var context = WorkflowSupport.context();
        String tokenScope = "workflow-instances|" + scope.kind() + "|" + status
                + "|" + ticketId + "|" + workflowVersionId;
        WorkflowCursorTokenService.Cursor cursor = cursorTokens.decode(
                pageToken, context.tenantId(), tokenScope, pageSize, normalizedSort);
        List<InstanceRow> rows = repository.findInstances(context.tenantId(), status,
                ticketId, workflowVersionId, scope.approverUserId(),
                !normalizedSort.startsWith("-"),
                cursor == null ? null : Instant.parse(cursor.lastValue()),
                cursor == null ? null : cursor.lastId(), pageSize);
        boolean more = rows.size() > pageSize;
        if (more) {
            rows = rows.subList(0, pageSize);
        }
        String next = null;
        if (more && !rows.isEmpty()) {
            InstanceRow last = rows.get(rows.size() - 1);
            next = cursorTokens.encode(context.tenantId(), tokenScope, pageSize,
                    normalizedSort, last.startedAt().toString(), last.workflowInstanceId());
        }
        return new WorkflowPage<>(rows.stream().map(WorkflowRuntimeResponseMapper::instanceResponse).toList(),
                next, normalizedSort);
    }

    /**
     * 按声明的租户与业务条件查询工作流实例，供调用方核对可见性、状态或幂等关系。
     */
    public WorkflowDtos.WorkflowInstanceResponse getInstance(UUID workflowInstanceId) {
        Scope scope = readScope();
        InstanceRow row = repository.findInstance(WorkflowSupport.context().tenantId(),
                        workflowInstanceId, scope.approverUserId(), false)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND",
                        "Workflow Instance 不存在。"));
        return instanceResponse(row);
    }

    /**
     * 分页查询当前租户可见的审批任务，按排序及游标生成稳定结果。
     */
    public WorkflowPage<WorkflowDtos.ApprovalTaskResponse> listTasks(
            String status, Integer levelNo, UUID workflowInstanceId, int pageSize,
            String pageToken, String sort) {
        Scope scope = readScope();
        if (status != null && !TASK_STATUSES.contains(status)) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "Approval Task 状态无效。");
        }
        if (levelNo != null && levelNo != 1 && levelNo != 2) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "levelNo 只接受 1 或 2。");
        }
        String normalizedSort = normalize(sort, "-createdAt", TASK_SORTS);
        var context = WorkflowSupport.context();
        String tokenScope = "approval-tasks|" + scope.kind() + "|" + status
                + "|" + levelNo + "|" + workflowInstanceId;
        WorkflowCursorTokenService.Cursor cursor = cursorTokens.decode(
                pageToken, context.tenantId(), tokenScope, pageSize, normalizedSort);
        List<TaskRow> rows = repository.findTasks(context.tenantId(), status, levelNo,
                workflowInstanceId, scope.approverUserId(), normalizedSort,
                cursor == null ? null : cursor.lastValue(),
                cursor == null ? null : cursor.lastId(), pageSize);
        boolean more = rows.size() > pageSize;
        if (more) {
            rows = rows.subList(0, pageSize);
        }
        String next = null;
        if (more && !rows.isEmpty()) {
            TaskRow last = rows.get(rows.size() - 1);
            Instant sortValue = normalizedSort.endsWith("decidedAt")
                    ? (last.decidedAt() == null ? last.createdAt() : last.decidedAt())
                    : last.createdAt();
            next = cursorTokens.encode(context.tenantId(), tokenScope, pageSize,
                    normalizedSort, sortValue.toString(), last.approvalTaskId());
        }
        return new WorkflowPage<>(rows.stream().map(WorkflowRuntimeResponseMapper::taskResponse).toList(),
                next, normalizedSort);
    }

    /**
     * 统计当前可见范围内的待办审批任务，供工作台摘要展示。
     */
    public java.util.Map<String, Long> taskSummary() {
        Scope scope = readScope();
        return java.util.Map.of("pendingCount", repository.countPendingTasks(
                WorkflowSupport.context().tenantId(), scope.approverUserId()));
    }

    /**
     * 按声明的租户与业务条件查询审批任务，供调用方核对可见性、状态或幂等关系。
     */
    public WorkflowDtos.ApprovalTaskResponse getTask(UUID approvalTaskId) {
        Scope scope = readScope();
        TaskRow row = repository.findTask(WorkflowSupport.context().tenantId(),
                        approvalTaskId, false)
                .filter(task -> scope.approverUserId() == null
                        || scope.approverUserId().equals(task.approverUserId()))
                .orElseThrow(() -> taskNotAssigned());
        return taskResponse(row);
    }

    /**
     * 查询实例的审批交接历史，供授权用户追溯原办理人及变更原因。
     */
    public List<WorkflowDtos.ApproverChangeResponse> approverChanges(UUID instanceId) {
        getInstance(instanceId);
        return repository.findApproverChanges(WorkflowSupport.context().tenantId(), instanceId).stream()
                .map(row -> new WorkflowDtos.ApproverChangeResponse(row.changeId(), row.levelNo(), row.previous(),
                        row.next(), row.actorId(), row.reason(), row.changedAt())).toList();
    }

    /**
     * 按当前权限选取租户级或固定审批人范围，防止列表扩大可见数据。
     */
    private Scope readScope() {
        if (WorkflowSupport.hasAny(PermissionCode.WORKFLOW_RUNTIME_READ_TENANT)) {
            WorkflowSupport.require(PermissionCode.WORKFLOW_RUNTIME_READ_TENANT);
            return new Scope("TENANT", null);
        }
        WorkflowSupport.require(PermissionCode.APPROVAL_READ_ASSIGNED);
        return new Scope("ASSIGNED", WorkflowSupport.actorId());
    }

    /**
     * 统一表达任务不可见或未分配，避免泄露无办理权限的审批事实。
     */
    private static ApiException taskNotAssigned() {
        return ApiException.notFound("APPROVAL_TASK_NOT_ASSIGNED",
                "Approval Task 不存在或不属于当前审批人。");
    }

    /**
     * 从白名单选择请求排序或默认排序，拒绝不受支持的表达式。
     */
    private static String normalize(String value, String fallback, Set<String> allowed) {
        String result = value == null || value.isBlank() ? fallback : value;
        if (!allowed.contains(result)) {
            throw ApiException.badRequest("SORT_UNSUPPORTED", "不支持的排序字段。");
        }
        return result;
    }

    /**
     * 工作流不可变数据快照，组合声明字段供当前业务处理阶段传递完整事实。
     */
    private record Scope(String kind, UUID approverUserId) {
    }
}
