package com.flowdesk.workflow.model.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * 工作流持久化行快照，保存数据库查询得到的业务字段，供服务校验与响应映射使用。
 */
public record InstanceRow(UUID workflowInstanceId, UUID ticketId,
                          byte[] startIntentKey, UUID definitionId,
                          UUID workflowVersionId, int workflowVersionNo,
                          String inputSnapshotJson, byte[] inputSnapshotSha256,
                          boolean conditionResult, String status, Integer currentLevel,
                          Instant startedAt, Instant completedAt, long lockVersion,
                          Instant createdAt, Instant updatedAt, UUID startMessageId,
                          UUID lastActorId) {
    /**
     * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
     */
    public InstanceRow {
        startIntentKey = startIntentKey.clone();
        inputSnapshotSha256 = inputSnapshotSha256.clone();
    }

    /**
     * 返回启动意图键的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override public byte[] startIntentKey() { return startIntentKey.clone(); }
    /**
     * 返回输入快照摘要的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override public byte[] inputSnapshotSha256() { return inputSnapshotSha256.clone(); }
}
