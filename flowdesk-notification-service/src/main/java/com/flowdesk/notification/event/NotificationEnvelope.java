package com.flowdesk.notification.event;

import com.flowdesk.common.notification.NotificationEventContract;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 已越过租户信任边界的不可变传输信封，不混入尚未通过语义校验的业务字段。 */
public record NotificationEnvelope(
        NotificationEventContract.Binding binding,
        UUID tenantId,
        UUID messageId,
        String messageKey,
        byte[] businessIntentKey,
        byte[] publicationKey,
        String aggregateType,
        UUID aggregateId,
        UUID correlationId,
        UUID causationId,
        Instant occurredAt,
        byte[] bodyBytes,
        byte[] bodySha256,
        boolean dlq) {

    /**
     * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
     */
    public NotificationEnvelope {
        binding = Objects.requireNonNull(binding, "binding");
        tenantId = Objects.requireNonNull(tenantId, "tenantId");
        messageId = Objects.requireNonNull(messageId, "messageId");
        messageKey = Objects.requireNonNull(messageKey, "messageKey");
        businessIntentKey = Objects.requireNonNull(businessIntentKey, "businessIntentKey").clone();
        publicationKey = Objects.requireNonNull(publicationKey, "publicationKey").clone();
        aggregateType = Objects.requireNonNull(aggregateType, "aggregateType");
        aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        bodyBytes = Objects.requireNonNull(bodyBytes, "bodyBytes").clone();
        bodySha256 = Objects.requireNonNull(bodySha256, "bodySha256").clone();
    }

    /**
     * 返回业务意图键的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override public byte[] businessIntentKey() { return businessIntentKey.clone(); }
    /**
     * 返回消息发布键的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override public byte[] publicationKey() { return publicationKey.clone(); }
    /**
     * 返回原始消息字节的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override public byte[] bodyBytes() { return bodyBytes.clone(); }
    /**
     * 返回消息体摘要的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override public byte[] bodySha256() { return bodySha256.clone(); }

    /**
     * 返回信封绑定的冻结 Topic，供传输适配与契约核验使用。
     */
    public String topic() { return binding.topic(); }
    /**
     * 返回信封绑定的消费组，避免同一载荷被送入错误业务处理方向。
     */
    public String consumerGroup() { return binding.consumerGroup(); }
    /**
     * 返回契约逻辑目标，避免业务校验直接依赖代理内部的重试路由。
     */
    public String logicalDestination() { return topic() + "|" + consumerGroup(); }
}
