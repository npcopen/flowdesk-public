package com.flowdesk.ticket.dto;

import com.flowdesk.common.validation.NoControlCharacters;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** 目录完整配置的写入模型，包含风险等级及高风险目录所需的审批版本引用。 */
public record CatalogWriteRequest(
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}") String itemCode,
        @NotBlank @Size(max = 128) @NoControlCharacters String itemName,
        @Size(max = 1000) @NoControlCharacters String description,
        @NotNull RiskLevel riskLevel,
        @NotNull UUID slaPolicyId,
        @Valid WorkflowBinding workflowRef,
        @NotNull Status status) {

    /**
     * 服务目录风险等级，决定新工单是否进入审批及其规则绑定要求。
     */
    public enum RiskLevel {
        NORMAL,
        HIGH
    }

    /**
     * 允许的状态枚举，限制请求或传输结果只能使用当前契约定义的状态。
     */
    public enum Status {
        ACTIVE,
        INACTIVE
    }

    /**
     * 目录绑定的工作流定义与版本快照，明确未来工单采用的审批规则。
     */
    public record WorkflowBinding(
            @NotNull UUID definitionId,
            @NotNull UUID workflowVersionId,
            @Min(1) int versionNo) {
    }
}
