package com.flowdesk.ticket.model.incident.result;

import java.time.Instant;
import java.util.UUID;

/**
 * 已领取消息快照，包含本次令牌、锁版本及原始发布内容。
 */
public record Claimed(UUID tenantId, UUID outboxId, UUID messageId, byte[] publicationKey,
                      byte[] intent, UUID incidentId, byte[] body, byte[] hash,
                      UUID correlationId, UUID causationId, int attemptCount, String token,
                      Instant lockedAt, long lockVersion, Instant createdAt) {
    /**
     * 保存记录字段，并对可变字节数组作防御性复制，避免调用方修改已构造的业务快照。
     */
    public Claimed {
        publicationKey = publicationKey.clone();
        intent = intent.clone();
        body = body.clone();
        hash = hash.clone();
    }
    /**
     * 返回消息发布幂等键的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] publicationKey() { return publicationKey.clone(); }
    /**
     * 返回规范业务意图的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] intent() { return intent.clone(); }
    /**
     * 返回消息原文字节的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] body() { return body.clone(); }
    /**
     * 返回内容摘要的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] hash() { return hash.clone(); }
}
