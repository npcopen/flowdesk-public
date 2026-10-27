package com.flowdesk.workflow.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 反向事件投递的候选、领取及恢复快照，在短数据库事务与事务外网络发送之间传递稳定事实。
 */
public final class WorkflowOutboxModels {
    /**
     * 禁止直接实例化工具容器，当前类型通过静态方法提供共享规则和转换。
     */
    private WorkflowOutboxModels() { }

    /**
     * 扫描所得的 Outbox 候选快照，携带状态、版本和租约信息供后续条件领取。
     */
    public record Candidate(UUID tenantId, UUID outboxId, String status, int attemptCount,
                            String lockedBy, UUID correlationId, long lockVersion) { }

    /**
     * 已取得处理权的消息快照，结算须携带本次令牌、版本及次数，避免过期处理者覆盖新结果。
     */
    public record Claimed(UUID tenantId, UUID outboxId, UUID messageId, byte[] publicationKey,
                          byte[] businessIntentKey, String aggregateType, UUID aggregateId,
                          String contractRef, int contractVersion, byte[] bodyBytes,
                          byte[] bodySha256, UUID correlationId, UUID causationId,
                          int attemptCount, String claimToken, Instant claimedAt,
                          long lockVersion, Instant createdAt) {
        /**
         * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
         */
        public Claimed {
            publicationKey = publicationKey.clone(); businessIntentKey = businessIntentKey.clone();
            bodyBytes = bodyBytes.clone(); bodySha256 = bodySha256.clone();
        }
        /**
         * 返回消息发布键的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override public byte[] publicationKey() { return publicationKey.clone(); }
        /**
         * 返回业务意图键的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override public byte[] businessIntentKey() { return businessIntentKey.clone(); }
        /**
         * 返回原始消息字节的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override public byte[] bodyBytes() { return bodyBytes.clone(); }
        /**
         * 返回消息体摘要的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override public byte[] bodySha256() { return bodySha256.clone(); }
    }

    /**
     * 消息投递恢复快照，供核对原业务意图与恢复生命周期是否一致。
     */
    public record Recovery(byte[] intent, String state) { }
}
