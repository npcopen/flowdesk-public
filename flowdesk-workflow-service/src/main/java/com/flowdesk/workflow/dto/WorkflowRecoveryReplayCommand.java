package com.flowdesk.workflow.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * 工作流接口输入模型，声明调用方可提交的字段与校验约束，供对应命令入口解析。
 */
public record WorkflowRecoveryReplayCommand(String reason) {
    /**
     * 拒绝恢复命令中未声明的字段，保持请求体契约严格可追溯。
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignoredValue) {
        throw new IllegalArgumentException("Workflow replay request contains unknown field: " + field);
    }
}
