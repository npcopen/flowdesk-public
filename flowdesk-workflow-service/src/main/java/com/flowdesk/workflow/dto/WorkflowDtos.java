package com.flowdesk.workflow.dto;

import com.flowdesk.common.validation.NoControlCharacters;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 流程定义及审批运行时的请求、响应模型（API-034～048/062）。
 */
public final class WorkflowDtos {

    /**
     * 禁止直接实例化工具容器，相关静态规则与转换由调用方按业务需要复用。
     */
    private WorkflowDtos() {
    }

    /**
     * 工作流对外响应快照，固定暴露当前接口允许展示的状态与关联信息。
     */
    public record WorkflowDefinitionResponse(
            UUID definitionId, String definitionCode, String definitionName,
            String status, long lockVersion, Instant createdAt, Instant updatedAt) {
    }

    /**
     * 工作流不可变数据快照，组合声明字段供当前业务处理阶段传递完整事实。
     */
    public record Condition(
            String fieldCode, String operator, String valueType, String value) {
    }

    /**
     * 工作流对外响应快照，固定暴露当前接口允许展示的状态与关联信息。
     */
    public record WorkflowDraftResponse(
            UUID draftId, UUID definitionId, String status, Condition condition,
            int approvalLevelCount, UUID level1ApproverUserId, UUID level2ApproverUserId,
            String validationState, String validationDigest, Instant validatedAt,
            Instant consumedAt, long lockVersion, Instant createdAt, Instant updatedAt) {
    }

    /**
     * 工作流接口输入模型，声明调用方可提交的字段与校验约束，供对应命令入口解析。
     */
    public record WorkflowDraftWriteRequest(
            @NotNull @Valid ConditionWrite condition,
            @NotNull UUID level1ApproverUserId,
            @NotNull UUID level2ApproverUserId) {
    }

    /**
     * 工作流接口输入模型，声明调用方可提交的字段与校验约束，供对应命令入口解析。
     */
    public record ConditionWrite(
            @NotBlank @Pattern(regexp = "SERVICE_RISK_LEVEL") String fieldCode,
            @NotBlank @Pattern(regexp = "EQUALS") String operator,
            @NotBlank @Pattern(regexp = "STRING") String valueType,
            @NotBlank @Pattern(regexp = "NORMAL|HIGH") String value) {
    }

    /**
     * 工作流对外响应快照，固定暴露当前接口允许展示的状态与关联信息。
     */
    public record WorkflowValidationResponse(
            UUID draftId, boolean valid, String validationDigest,
            List<com.flowdesk.common.web.FieldViolation> violations,
            Instant validatedAt, long lockVersion) {
    }

    /**
     * 工作流对外响应快照，固定暴露当前接口允许展示的状态与关联信息。
     */
    public record WorkflowVersionResponse(
            UUID workflowVersionId, UUID definitionId, int versionNo, String status,
            Condition condition, int approvalLevelCount,
            UUID level1ApproverUserId, UUID level2ApproverUserId,
            String snapshotSha256, Instant publishedAt, UUID publishedByUserId) {
    }

    /**
     * 工作流不可变数据快照，组合声明字段供当前业务处理阶段传递完整事实。
     */
    public record WorkflowVersionRef(
            UUID definitionId, UUID workflowVersionId, int versionNo) {
    }

    /**
     * 工作流不可变数据快照，组合声明字段供当前业务处理阶段传递完整事实。
     */
    public record InputSnapshot(String schemaVersion, String serviceRiskLevel) {
    }

    /**
     * 工作流对外响应快照，固定暴露当前接口允许展示的状态与关联信息。
     */
    public record WorkflowInstanceResponse(
            UUID workflowInstanceId, UUID ticketId, WorkflowVersionRef workflowVersionRef,
            InputSnapshot inputSnapshot, String inputSnapshotSha256,
            boolean conditionResult, String status, Integer currentLevel,
            Instant startedAt, Instant completedAt, long lockVersion) {
    }

    /**
     * 工作流对外响应快照，固定暴露当前接口允许展示的状态与关联信息。
     */
    public record ApprovalTaskResponse(
            UUID approvalTaskId, UUID workflowInstanceId, int levelNo,
            UUID approverUserId, String status, String decisionType,
            String decisionReason, Instant decidedAt, long lockVersion,
            Instant createdAt, Instant updatedAt) {
    }

    /**
     * 工作流接口输入模型，声明调用方可提交的字段与校验约束，供对应命令入口解析。
     */
    public record ApprovalDecisionRequest(
            @Size(max = 1000) @NoControlCharacters String reason) {
    }

    /**
     * 工作流接口输入模型，声明调用方可提交的字段与校验约束，供对应命令入口解析。
     */
    public record ApproverChangeRequest(
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(2) int levelNo,
            @NotNull UUID approverUserId,
            @NotBlank @Size(max = 1000) @NoControlCharacters String reason) { }

    /**
     * 工作流对外响应快照，固定暴露当前接口允许展示的状态与关联信息。
     */
    public record ApproverChangeResponse(UUID changeId, int levelNo, UUID previousApproverUserId,
                                         UUID approverUserId, UUID changedByUserId,
                                         String reason, Instant changedAt) { }
}
