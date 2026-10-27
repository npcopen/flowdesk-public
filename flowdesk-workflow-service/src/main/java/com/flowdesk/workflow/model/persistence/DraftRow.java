package com.flowdesk.workflow.model.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * 工作流持久化行快照，保存数据库查询得到的业务字段，供服务校验与响应映射使用。
 */
public record DraftRow(UUID draftId, UUID definitionId, String status,
                       String conditionFieldCode, String conditionOperator,
                       String conditionValueType, String conditionValueText,
                       int approvalLevelCount, UUID level1ApproverUserId,
                       UUID level2ApproverUserId, String validationState,
                       byte[] validationDigest, Instant validatedAt, Instant consumedAt,
                       long lockVersion, Instant createdAt, Instant updatedAt) {
    /**
     * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
     */
    public DraftRow {
        validationDigest = validationDigest == null ? null : validationDigest.clone();
    }

    /**
     * 返回校验摘要的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override
    public byte[] validationDigest() {
        return validationDigest == null ? null : validationDigest.clone();
    }
}
