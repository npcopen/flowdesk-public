package com.flowdesk.ticket.api;

import com.flowdesk.common.validation.NoControlCharacters;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response records for the Ticket HTTP contract (API-SPEC §5.3). */
public final class TicketDtos {

    private TicketDtos() {
    }

    // ----- catalog / SLA ---------------------------------------------------------

    public record ServiceCatalogItemResponse(
            UUID catalogItemId,
            String itemCode,
            String itemName,
            String description,
            String riskLevel,
            boolean requiresApproval,
            WorkflowRef workflowRef,
            UUID slaPolicyId,
            String status,
            long lockVersion,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record WorkflowRef(UUID definitionId, UUID workflowVersionId, int versionNo) {
    }

    public static final class ServiceCatalogPatchRequest {
        @Size(min = 1, max = 128)
        @Pattern(regexp = "[\\s\\S]*\\S[\\s\\S]*", message = "itemName 不能为空白。")
        @NoControlCharacters
        private String itemName;

        @Size(max = 1000)
        @NoControlCharacters
        private String description;

        private UUID slaPolicyId;
        private Status status;
        private boolean descriptionPresent;

        public String itemName() {
            return itemName;
        }

        public void setItemName(String itemName) {
            this.itemName = itemName;
        }

        public String description() {
            return description;
        }

        @JsonSetter("description")
        public void setDescription(String description) {
            this.description = description;
            this.descriptionPresent = true;
        }

        public boolean descriptionPresent() {
            return descriptionPresent;
        }

        public UUID slaPolicyId() {
            return slaPolicyId;
        }

        public void setSlaPolicyId(UUID slaPolicyId) {
            this.slaPolicyId = slaPolicyId;
        }

        public Status status() {
            return status;
        }

        public void setStatus(Status status) {
            this.status = status;
        }

        public enum Status { ACTIVE, INACTIVE }
    }

    public record SlaPolicySummaryResponse(
            UUID slaPolicyId,
            String policyCode,
            String policyName,
            long firstResponseSeconds,
            long resolutionSeconds,
            Long firstResponseReminderBeforeSeconds,
            Long resolutionReminderBeforeSeconds,
            String status,
            long lockVersion) {
    }

    // ----- ticket lifecycle ------------------------------------------------------

    public record TicketCreateRequest(
            @NotNull UUID catalogItemId,
            @NotBlank @Size(max = 255) @NoControlCharacters String subject,
            @NotBlank @Size(max = 10000) @NoControlCharacters String description,
            UUID previousAttemptTicketId) {
    }

    public record AssignmentCommand(
            @NotNull UUID assigneeUserId) {
    }

    public record TransferCommand(
            @NotNull UUID newAssigneeUserId,
            @NotBlank @Size(max = 1000) @NoControlCharacters String reason) {
    }

    public record CommentCreateRequest(
            @NotBlank @Size(max = 10000) @NoControlCharacters String body) {
    }

    public record ResolveCommand(
            @NotBlank @Size(max = 1000) @NoControlCharacters String resolutionSummary) {
    }

    public record CloseOrReopenCommand(
            @Size(max = 1000) @NoControlCharacters String reason) {
    }

    public record TicketResponse(
            UUID ticketId,
            String ticketNo,
            String originType,
            UUID catalogItemId,
            UUID requesterUserId,
            UUID previousAttemptTicketId,
            String subject,
            String description,
            String status,
            UUID assigneeUserId,
            WorkflowRef workflowRef,
            Instant firstProcessableAt,
            Instant resolvedAt,
            Instant closedAt,
            Instant lastReopenedAt,
            int reopenCount,
            long lockVersion,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record TicketCommentResponse(
            UUID commentId,
            UUID ticketId,
            String body,
            UUID authorUserId,
            Instant createdAt,
            UUID correlationId) {
    }

    public record TimelineEntryResponse(
            UUID timelineId,
            UUID ticketId,
            String eventType,
            String summary,
            String relatedRefType,
            UUID relatedRefId,
            String actorType,
            UUID actorId,
            Instant occurredAt,
            UUID correlationId) {
    }

    public record SlaStatusResponse(UUID ticketId, List<SlaClockStatus> clocks, List<SlaClockStatus> historyClocks) {
    }

    public record WorkflowStartStatusResponse(
            UUID ticketId,
            String status,
            UUID workflowInstanceId,
            Instant requestedAt,
            Instant establishedAt,
            Instant recoveryRequiredAt,
            String recoveryReasonCode,
            int retryCount,
            Instant lastAttemptAt,
            long lockVersion,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record SlaClockStatus(
            UUID slaClockId,
            UUID slaPolicyId,
            String clockType,
            String status,
            Instant startedAt,
            Instant deadlineAt,
            Instant reminderDueAt,
            long policyDurationSeconds,
            Long policyReminderBeforeSeconds,
            Instant milestoneAt,
            Instant breachedAt,
            Instant satisfiedAt,
            List<SlaTriggerStatus> triggers,
            long lockVersion, int cycleNo) {
    }

    public record SlaTriggerStatus(String stage, Instant dueAt, Instant triggeredAt, String notificationStatus) {
    }
}
