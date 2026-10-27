package com.flowdesk.workflow.service;

import com.flowdesk.workflow.dto.WorkflowDtos;
import java.util.List;
import java.util.UUID;

/**
 * 工作流业务用例契约，供入口通过稳定方法调用查询或命令处理，并统一约束可见范围及副作用。
 */
public interface WorkflowRuntimeQueryService {
    /**
     * 分页查询当前租户可见的工作流实例，按排序及游标生成稳定结果。
     */
    WorkflowPage<WorkflowDtos.WorkflowInstanceResponse> listInstances(
            String status, UUID ticketId, UUID workflowVersionId, int pageSize,
            String pageToken, String sort);

    /**
     * 按声明的租户与业务条件查询工作流实例，供调用方核对可见性、状态或幂等关系。
     */
    WorkflowDtos.WorkflowInstanceResponse getInstance(UUID workflowInstanceId);

    /**
     * 分页查询当前租户可见的审批任务，按排序及游标生成稳定结果。
     */
    WorkflowPage<WorkflowDtos.ApprovalTaskResponse> listTasks(
            String status, Integer levelNo, UUID workflowInstanceId, int pageSize,
            String pageToken, String sort);

    /**
     * 统计当前可见范围内的待办审批任务，供工作台摘要展示。
     */
    java.util.Map<String, Long> taskSummary();

    /**
     * 按声明的租户与业务条件查询审批任务，供调用方核对可见性、状态或幂等关系。
     */
    WorkflowDtos.ApprovalTaskResponse getTask(UUID approvalTaskId);

    /**
     * 查询实例的审批交接历史，供授权用户追溯原办理人及变更原因。
     */
    List<WorkflowDtos.ApproverChangeResponse> approverChanges(UUID instanceId);
}
