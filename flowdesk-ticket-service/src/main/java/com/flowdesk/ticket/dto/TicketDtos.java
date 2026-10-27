package com.flowdesk.ticket.dto;

import com.flowdesk.common.validation.NoControlCharacters;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 工单 HTTP 契约的请求与响应模型（API-SPEC §5.3），集中声明字段校验和对外数据结构。 */
public final class TicketDtos {

    /**
     * 私有构造方法，阻止工具类实例化，调用方应使用静态入口。
     */
    private TicketDtos() {
    }

    // 目录与 SLA 模型

    /**
     * 目录项响应，展示状态、风险及已绑定的规则引用。
     */
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

    /**
     * 对外工作流版本引用，固定目录或工单使用的定义与版本。
     */
    public record WorkflowRef(UUID definitionId, UUID workflowVersionId, int versionNo) {
    }

    /**
     * 目录局部更新载荷，区分字段未出现与显式更新，以准确表达 PATCH 意图。
     */
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

        /**
         * 返回当前目录项名称，供请求处理或配置读取。
         */
        public String itemName() {
            return itemName;
        }

        /**
         * 设置目录项名称，供请求绑定或配置更新使用。
         */
        public void setItemName(String itemName) {
            this.itemName = itemName;
        }

        /**
         * 返回当前描述，供请求处理或配置读取。
         */
        public String description() {
            return description;
        }

        /**
         * 设置描述，供请求绑定或配置更新使用。
         */
        @JsonSetter("description")
        public void setDescription(String description) {
            this.description = description;
            this.descriptionPresent = true;
        }

        /**
         * 返回当前描述字段是否显式出现，供请求处理或配置读取。
         */
        public boolean descriptionPresent() {
            return descriptionPresent;
        }

        /**
         * 返回当前SLA 策略标识，供请求处理或配置读取。
         */
        public UUID slaPolicyId() {
            return slaPolicyId;
        }

        /**
         * 设置SLA 策略标识，供请求绑定或配置更新使用。
         */
        public void setSlaPolicyId(UUID slaPolicyId) {
            this.slaPolicyId = slaPolicyId;
        }

        /**
         * 返回当前状态，供请求处理或配置读取。
         */
        public Status status() {
            return status;
        }

        /**
         * 设置状态，供请求绑定或配置更新使用。
         */
        public void setStatus(Status status) {
            this.status = status;
        }

        /**
         * 允许的状态枚举，限制请求或传输结果只能使用当前契约定义的状态。
         */
        public enum Status { ACTIVE, INACTIVE }
    }

    /**
     * SLA 策略摘要，用于策略列表及目录配置选择。
     */
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

    // 工单生命周期模型

    /**
     * 人工建单请求，携带目录、主题、描述及可选的前次被拒申请引用。
     */
    public record TicketCreateRequest(
            @NotNull UUID catalogItemId,
            @NotBlank @Size(max = 255) @NoControlCharacters String subject,
            @NotBlank @Size(max = 10000) @NoControlCharacters String description,
            UUID previousAttemptTicketId) {
    }

    /**
     * 分派命令，指定目标处理人，由服务层核验其资格。
     */
    public record AssignmentCommand(
            @NotNull UUID assigneeUserId) {
    }

    /**
     * 转派命令，记录新处理人及本次转派原因。
     */
    public record TransferCommand(
            @NotNull UUID newAssigneeUserId,
            @NotBlank @Size(max = 1000) @NoControlCharacters String reason) {
    }

    /**
     * 评论创建载荷，评论内容经接口校验后以幂等键去重。
     */
    public record CommentCreateRequest(
            @NotBlank @Size(max = 10000) @NoControlCharacters String body) {
    }

    /**
     * 解决命令，携带解决说明，受当前版本与生命周期状态约束。
     */
    public record ResolveCommand(
            @NotBlank @Size(max = 1000) @NoControlCharacters String resolutionSummary) {
    }

    /**
     * 关闭或重开工单的原因载荷，允许状态由对应命令入口核验。
     */
    public record CloseOrReopenCommand(
            @Size(max = 1000) @NoControlCharacters String reason) {
    }

    /**
     * 工单对外详情快照，包含生命周期、参与人、规则引用和乐观锁版本。
     */
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

    /**
     * 对外评论快照，保留内容、作者与创建时间。
     */
    public record TicketCommentResponse(
            UUID commentId,
            UUID ticketId,
            String body,
            UUID authorUserId,
            Instant createdAt,
            UUID correlationId) {
    }

    /**
     * 对外时间线事件快照，展示业务操作、状态变化与发生时间。
     */
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

    /**
     * 工单 SLA 响应，汇总各时钟及提醒、超时触发事实。
     */
    public record SlaStatusResponse(UUID ticketId, List<SlaClockStatus> clocks, List<SlaClockStatus> historyClocks) {
    }

    /**
     * 工作流启动进度快照，区分待投递、已建立与待恢复状态。
     */
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

    /**
     * 单个 SLA 时钟响应，保留目标时间、周期与达成状态。
     */
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

    /**
     * SLA 触发响应，保留阶段、触发时间和通知收件人。
     */
    public record SlaTriggerStatus(String stage, Instant dueAt, Instant triggeredAt, String notificationStatus) {
    }
}
