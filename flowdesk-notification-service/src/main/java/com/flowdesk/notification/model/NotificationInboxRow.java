package com.flowdesk.notification.model;
import java.util.*;
import java.time.Instant;

/**
 * 通知持久化行快照，保存数据库查询得到的业务字段，供服务校验与响应映射使用。
 */
public record NotificationInboxRow(UUID inboxId, String producerService, UUID messageId, String messageKey,
                           byte[] businessIntentKey, byte[] publicationKey, String contractRef,
                           int contractVersion, String aggregateType, UUID aggregateId,
                           byte[] bodySha256, byte[] bodyBytes, String topic, String consumerGroup,
                           String logicalDestination, String direction, UUID correlationId,
                           UUID causationId, Instant occurredAt, String status, String resultType,
                           UUID resultId, int attemptCount, String lastErrorCode,
                           Instant firstReceivedAt, Instant lastAttemptAt, Instant processedAt,
                           long lockVersion, Instant createdAt, Instant updatedAt) {
        /**
         * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
         */
        public NotificationInboxRow {
            businessIntentKey = businessIntentKey == null ? null : businessIntentKey.clone();
            publicationKey = publicationKey == null ? null : publicationKey.clone();
            bodySha256 = bodySha256 == null ? null : bodySha256.clone();
            bodyBytes = bodyBytes == null ? null : bodyBytes.clone();
        }
        /**
         * 返回业务意图键的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override public byte[] businessIntentKey() { return businessIntentKey == null ? null : businessIntentKey.clone(); }
        /**
         * 返回消息发布键的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override public byte[] publicationKey() { return publicationKey == null ? null : publicationKey.clone(); }
        /**
         * 返回消息体摘要的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override public byte[] bodySha256() { return bodySha256 == null ? null : bodySha256.clone(); }
        /**
         * 返回原始消息字节的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override public byte[] bodyBytes() { return bodyBytes == null ? null : bodyBytes.clone(); }
    }
