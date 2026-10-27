package com.flowdesk.ticket.reverse;

import java.time.Instant;
import java.util.UUID;

/** 通过信封与载荷校验的流程反向事件，封装可信租户及工单引用并复制可变字节数组。 */
public record AcceptedWorkflowReverse(
        WorkflowReverseContract.Binding binding,
        UUID tenantId, UUID messageId, byte[] businessIntentKey,
        UUID workflowInstanceId, UUID ticketId, byte[] startIntentKey,
        UUID workflowDefinitionId, UUID workflowVersionId, int versionNo,
         String instanceStatus, String outcome, Boolean conditionResult, Instant businessOccurredAt,
        UUID correlationId, UUID causationId, Instant envelopeOccurredAt,
        byte[] bodyBytes, byte[] bodySha256) {
    /**
     * 保存记录字段，并对可变字节数组作防御性复制，避免调用方修改已构造的业务快照。
     */
    public AcceptedWorkflowReverse {
        businessIntentKey = businessIntentKey.clone();
        startIntentKey = startIntentKey.clone();
        bodyBytes = bodyBytes.clone();
        bodySha256 = bodySha256.clone();
    }
    /**
     * 返回业务意图键的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] businessIntentKey() { return businessIntentKey.clone(); }
    /**
     * 返回startIntentKey的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] startIntentKey() { return startIntentKey.clone(); }
    /**
     * 返回消息原文字节的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] bodyBytes() { return bodyBytes.clone(); }
    /**
     * 返回消息 SHA-256 摘要的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] bodySha256() { return bodySha256.clone(); }
    /**
     * 返回审批决策原因，供申请人时间线和通知展示。
     */
    public String decisionReason() {
        var node = new tools.jackson.databind.ObjectMapper().readTree(bodyBytes).get("decisionReason");
        return node != null && node.isString() ? node.stringValue() : null;
    }
}
