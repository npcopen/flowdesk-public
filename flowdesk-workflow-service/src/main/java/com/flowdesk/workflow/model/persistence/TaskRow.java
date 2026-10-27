package com.flowdesk.workflow.model.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * 工作流持久化行快照，保存数据库查询得到的业务字段，供服务校验与响应映射使用。
 */
public record TaskRow(UUID approvalTaskId, UUID workflowInstanceId, int levelNo,
                      UUID approverUserId, String status, String decisionType,
                      String decisionReason, byte[] decisionIntentKey,
                      Instant decidedAt, UUID decidedByUserId, long lockVersion,
                      Instant createdAt, Instant updatedAt) {
    /**
     * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
     */
    public TaskRow {
        decisionIntentKey = decisionIntentKey == null ? null : decisionIntentKey.clone();
    }

    /**
     * 返回决定意图键的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override public byte[] decisionIntentKey() {
        return decisionIntentKey == null ? null : decisionIntentKey.clone();
    }
}
