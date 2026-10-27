package com.flowdesk.workflow.service;

import java.util.UUID;

/**
 * 计算受限两级审批规则的固定快照摘要，用于验证草稿内容及已发布版本的一致性。
 */
public final class WorkflowRuleDigest {

    /**
     * 禁止直接实例化工具容器，相关静态规则与转换由调用方按业务需要复用。
     */
    private WorkflowRuleDigest() {
    }

    /**
     * 按固定字段顺序计算规则摘要，使校验与发布使用一致的内容依据。
     */
    public static byte[] digest(UUID tenantId, UUID definitionId, String fieldCode,
                                String operator, String valueType, String value,
                                UUID level1ApproverUserId, UUID level2ApproverUserId) {
        return WorkflowIdempotencyCodec.derivedKey("FD-WORKFLOW-VERSION-V1",
                tenantId, definitionId, fieldCode, operator, valueType, value,
                level1ApproverUserId, level2ApproverUserId);
    }
}
