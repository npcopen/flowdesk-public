package com.flowdesk.ticket.model.reverse;

import java.time.Instant;
import java.util.UUID;

/**
 * 收件箱事实快照，保留消息身份、原文摘要和处理结果。
 */
public record Inbox(UUID inboxId, UUID messageId, byte[] intent, String contractRef,
                    int contractVersion, byte[] body, byte[] bodyHash, UUID correlationId,
                    UUID causationId, Instant occurredAt, String status, String resultType,
                    UUID resultId, int attemptCount, String lastErrorCode, long lockVersion) {
    /**
     * 保存记录字段，并对可变字节数组作防御性复制，避免调用方修改已构造的业务快照。
     */
    public Inbox {
        intent = intent == null ? null : intent.clone();
        body = body == null ? null : body.clone();
        bodyHash = bodyHash == null ? null : bodyHash.clone();
    }
    /**
     * 返回规范业务意图的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] intent() { return intent == null ? null : intent.clone(); }
    /**
     * 返回消息原文字节的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] body() { return body == null ? null : body.clone(); }
    /**
     * 返回消息摘要的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] bodyHash() { return bodyHash == null ? null : bodyHash.clone(); }

    /** 为早期测试夹具保留的兼容构造器。 */
    public Inbox(UUID inboxId, UUID messageId, byte[] intent, String contractRef,
                 int contractVersion, byte[] body, byte[] bodyHash, String status,
                 String resultType, UUID resultId, int attemptCount,
                 String lastErrorCode, long lockVersion) {
        this(inboxId, messageId, intent, contractRef, contractVersion, body, bodyHash,
                null, null, null, status, resultType, resultId, attemptCount,
                lastErrorCode, lockVersion);
    }
}
