package com.flowdesk.workflow.start;

import java.time.Instant;
import java.util.UUID;

/**
 * 传输校验通过后的不可变启动消息；只有通过校验的信封才能用于建立租户上下文。
 */
public record AcceptedWorkflowStart(
        UUID tenantId,
        String producerService,
        UUID messageId,
        byte[] businessIntentKey,
        UUID ticketId,
        UUID workflowDefinitionId,
        UUID workflowVersionId,
        int versionNo,
        String serviceRiskLevel,
        UUID requesterUserId,
        UUID correlationId,
        UUID causationId,
        Instant occurredAt,
        String contractRef,
        int contractVersion,
        byte[] bodyBytes,
        byte[] bodySha256) {

    /**
     * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
     */
    public AcceptedWorkflowStart {
        businessIntentKey = businessIntentKey.clone();
        bodyBytes = bodyBytes.clone();
        bodySha256 = bodySha256.clone();
    }

    /**
     * 返回业务意图键的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override
    public byte[] businessIntentKey() {
        return businessIntentKey.clone();
    }

    /**
     * 返回原始消息字节的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override
    public byte[] bodyBytes() {
        return bodyBytes.clone();
    }

    /**
     * 返回消息体摘要的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override
    public byte[] bodySha256() {
        return bodySha256.clone();
    }
}
