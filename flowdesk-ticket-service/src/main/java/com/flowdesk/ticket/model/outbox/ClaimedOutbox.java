package com.flowdesk.ticket.model.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * 已领取启动发件箱快照，限定后续发送及结算所有权。
 */
public record ClaimedOutbox(
        UUID tenantId,
        UUID outboxId,
        UUID messageId,
        byte[] publicationKey,
        byte[] businessIntentKey,
        String aggregateType,
        UUID aggregateId,
        String contractRef,
        int contractVersion,
        byte[] bodyBytes,
        byte[] bodySha256,
        UUID correlationId,
        UUID causationId,
        int attemptCount,
        String claimToken,
        Instant claimedAt,
        long lockVersion,
        Instant createdAt) {

    /**
     * 保存记录字段，并对可变字节数组作防御性复制，避免调用方修改已构造的业务快照。
     */
    public ClaimedOutbox {
        publicationKey = publicationKey.clone();
        businessIntentKey = businessIntentKey.clone();
        bodyBytes = bodyBytes.clone();
        bodySha256 = bodySha256.clone();
    }

    /**
     * 返回消息发布幂等键的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override
    public byte[] publicationKey() {
        return publicationKey.clone();
    }

    /**
     * 返回业务意图键的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override
    public byte[] businessIntentKey() {
        return businessIntentKey.clone();
    }

    /**
     * 返回消息原文字节的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override
    public byte[] bodyBytes() {
        return bodyBytes.clone();
    }

    /**
     * 返回消息 SHA-256 摘要的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override
    public byte[] bodySha256() {
        return bodySha256.clone();
    }
}
