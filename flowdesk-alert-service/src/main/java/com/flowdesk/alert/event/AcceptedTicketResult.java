package com.flowdesk.alert.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 信封校验器输出的工单关联结果，供主消费、死信接纳及恢复处理共用。
 *
 * <p>businessIntentKey 标识本次关联结果，createTicketIntentKey 用来核对 Alert 原有建单请求，
 * ticketId 来自 Ticket 服务的权威结果。保留原始正文和摘要用于 Inbox 内容去重，数组进出均复制以保护校验结果。
 * 契约通过只表示消息格式和自带标识一致，仍需在事务内核对已有故障及关联事实。
 */
public record AcceptedTicketResult(
        UUID tenantId,
        UUID messageId,
        byte[] businessIntentKey,
        UUID incidentId,
        byte[] createTicketIntentKey,
        UUID ticketId,
        Instant establishedAt,
        UUID correlationId,
        UUID causationId,
        Instant envelopeOccurredAt,
        byte[] body,
        byte[] bodyHash) {
    /**
     * 构造不可变快照并复制可变字节数组，防止调用方在校验完成后修改原文、摘要或认证事实。
     */
    public AcceptedTicketResult {
        businessIntentKey = businessIntentKey.clone();
        createTicketIntentKey = createTicketIntentKey.clone();
        body = body.clone();
        bodyHash = bodyHash.clone();
    }

    /**
     * 返回业务意图摘要的防御性副本，避免调用方修改已验证消息的身份。
     */
    @Override
    public byte[] businessIntentKey() {
        return businessIntentKey.clone();
    }

    /**
     * 返回建单意图摘要的防御性副本，保证后续关联校验始终使用已验证的原始值。
     */
    @Override
    public byte[] createTicketIntentKey() {
        return createTicketIntentKey.clone();
    }

    /**
     * 返回原始正文的防御性副本，防止外部修改污染已验证或待恢复的消息快照。
     */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /**
     * 返回原始正文摘要的防御性副本，保持去重比较依据不可变。
     */
    @Override
    public byte[] bodyHash() {
        return bodyHash.clone();
    }
}
