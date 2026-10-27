package com.flowdesk.workflow.model.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * 工作流不可变数据快照，组合声明字段供当前业务处理阶段传递完整事实。
 */
public record ApprovalFinalizedOutbox(UUID tenantId, UUID outboxId, UUID messageId,
                                      byte[] publicationKey, byte[] businessIntentKey,
                                      UUID instanceId, String contractRef, byte[] body,
                                      byte[] bodySha256, UUID correlationId, UUID causationId,
                                      Instant now, Instant createdAt, UUID actorId,
                                      Instant updatedAt, UUID updatedBy) {
    /**
     * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
     */
    public ApprovalFinalizedOutbox {
        publicationKey = publicationKey.clone();
        businessIntentKey = businessIntentKey.clone();
        body = body.clone();
        bodySha256 = bodySha256.clone();
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
     * 返回消息体字节的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override public byte[] body() { return body.clone(); }
    /**
     * 返回消息体摘要的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override public byte[] bodySha256() { return bodySha256.clone(); }
}
