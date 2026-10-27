package com.flowdesk.workflow.service;

import com.flowdesk.workflow.model.persistence.*;
import com.flowdesk.workflow.dto.WorkflowDtos;
import com.flowdesk.workflow.model.persistence.InstanceRow;
import com.flowdesk.workflow.model.persistence.TaskRow;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HexFormat;

/**
 * 将已持久化的实例和任务事实投影为 API 响应，本类不执行权限检查或数据库访问。
 */
public final class WorkflowRuntimeResponseMapper {
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
     */
    private WorkflowRuntimeResponseMapper() {
    }

    /**
     * 转换工作流实例持久化快照，供读取和写入结果统一输出。
     */
    public static WorkflowDtos.WorkflowInstanceResponse instanceResponse(InstanceRow row) {
        try {
            JsonNode input = JSON.readTree(row.inputSnapshotJson());
            return new WorkflowDtos.WorkflowInstanceResponse(row.workflowInstanceId(),
                    row.ticketId(), new WorkflowDtos.WorkflowVersionRef(row.definitionId(),
                    row.workflowVersionId(), row.workflowVersionNo()),
                    new WorkflowDtos.InputSnapshot(input.path("schemaVersion").stringValue(),
                            input.path("serviceRiskLevel").stringValue()),
                    HexFormat.of().formatHex(row.inputSnapshotSha256()),
                    row.conditionResult(), row.status(), row.currentLevel(), row.startedAt(),
                    row.completedAt(), row.lockVersion());
        }
        catch (Exception invalidSnapshot) {
            throw new IllegalStateException("Persisted Workflow input snapshot is invalid",
                    invalidSnapshot);
        }
    }

    /**
     * 将审批任务转换为响应，HTTP 入口同时按任务版本设置 ETag。
     */
    public static WorkflowDtos.ApprovalTaskResponse taskResponse(TaskRow row) {
        return new WorkflowDtos.ApprovalTaskResponse(row.approvalTaskId(),
                row.workflowInstanceId(), row.levelNo(), row.approverUserId(), row.status(),
                row.decisionType(), row.decisionReason(), row.decidedAt(), row.lockVersion(),
                row.createdAt(), row.updatedAt());
    }
}
