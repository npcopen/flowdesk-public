package com.flowdesk.workflow.api;

import com.flowdesk.common.validation.NoControlCharacters;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API-034..048/062 request and response models. */
public final class WorkflowDtos {

    private WorkflowDtos() {
    }

    public record WorkflowDefinitionResponse(
            UUID definitionId, String definitionCode, String definitionName,
            String status, long lockVersion, Instant createdAt, Instant updatedAt) {
    }

    public record Condition(
            String fieldCode, String operator, String valueType, String value) {
    }

    public record WorkflowDraftResponse(
            UUID draftId, UUID definitionId, String status, Condition condition,
            int approvalLevelCount, UUID level1ApproverUserId, UUID level2ApproverUserId,
            String validationState, String validationDigest, Instant validatedAt,
            Instant consumedAt, long lockVersion, Instant createdAt, Instant updatedAt) {
    }

    public record WorkflowDraftWriteRequest(
            @NotNull @Valid ConditionWrite condition,
            @NotNull UUID level1ApproverUserId,
            @NotNull UUID level2ApproverUserId) {
    }

    public record ConditionWrite(
            @NotBlank @Pattern(regexp = "SERVICE_RISK_LEVEL") String fieldCode,
            @NotBlank @Pattern(regexp = "EQUALS") String operator,
            @NotBlank @Pattern(regexp = "STRING") String valueType,
            @NotBlank @Pattern(regexp = "NORMAL|HIGH") String value) {
    }

    public record WorkflowValidationResponse(
            UUID draftId, boolean valid, String validationDigest,
            List<com.flowdesk.common.web.FieldViolation> violations,
            Instant validatedAt, long lockVersion) {
    }

    public record WorkflowVersionResponse(
            UUID workflowVersionId, UUID definitionId, int versionNo, String status,
            Condition condition, int approvalLevelCount,
            UUID level1ApproverUserId, UUID level2ApproverUserId,
            String snapshotSha256, Instant publishedAt, UUID publishedByUserId) {
    }

    public record WorkflowVersionRef(
            UUID definitionId, UUID workflowVersionId, int versionNo) {
    }

    public record InputSnapshot(String schemaVersion, String serviceRiskLevel) {
    }

    public record WorkflowInstanceResponse(
            UUID workflowInstanceId, UUID ticketId, WorkflowVersionRef workflowVersionRef,
            InputSnapshot inputSnapshot, String inputSnapshotSha256,
            boolean conditionResult, String status, Integer currentLevel,
            Instant startedAt, Instant completedAt, long lockVersion) {
    }

    public record ApprovalTaskResponse(
            UUID approvalTaskId, UUID workflowInstanceId, int levelNo,
            UUID approverUserId, String status, String decisionType,
            String decisionReason, Instant decidedAt, long lockVersion,
            Instant createdAt, Instant updatedAt) {
    }

    public record ApprovalDecisionRequest(
            @Size(max = 1000) @NoControlCharacters String reason) {
    }

    public record ApproverChangeRequest(
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(2) int levelNo,
            @NotNull UUID approverUserId,
            @NotBlank @Size(max = 1000) @NoControlCharacters String reason) { }

    public record ApproverChangeResponse(UUID changeId, int levelNo, UUID previousApproverUserId,
                                         UUID approverUserId, UUID changedByUserId,
                                         String reason, Instant changedAt) { }
}
