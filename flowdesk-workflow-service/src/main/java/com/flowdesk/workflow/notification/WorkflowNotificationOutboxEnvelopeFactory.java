package com.flowdesk.workflow.notification;

import com.flowdesk.workflow.model.WorkflowNotificationOutboxModels;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.notification.NotificationWireCodec;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 依据已持久化的原始消息字节构建通知信封，避免重新序列化改变契约内容。
 */
final class WorkflowNotificationOutboxEnvelopeFactory {
    /**
     * 禁止直接实例化工具容器，当前类型通过静态方法提供共享规则和转换。
     */
    private WorkflowNotificationOutboxEnvelopeFactory() { }

    /**
     * 从已领取的持久化消息还原冻结信封，并核验身份、字节和摘要后交给传输层。
     */
    static WorkflowNotificationOutboxMessage create(
            WorkflowNotificationOutboxModels.Claimed row) {
        if (!NotificationEventContract.WORKFLOW.contractRef().equals(row.contractRef())
                || row.contractVersion() != NotificationEventContract.VERSION
                || !"NOTIFICATION_REQUEST".equals(row.aggregateType())) {
            throw new InvalidContract("Workflow Notification Outbox route is not frozen");
        }
        byte[] body = row.bodyBytes();
        if (body.length == 0 || body.length > NotificationEventContract.MAX_BODY_BYTES) {
            throw new InvalidContract("Notification body size is invalid");
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body));
        }
        catch (Exception invalid) {
            throw new InvalidContract("Notification body is not UTF-8");
        }
        if (row.businessIntentKey().length != 32 || row.publicationKey().length != 32
                || row.bodySha256().length != 32
                || !MessageDigest.isEqual(NotificationWireCodec.sha256(body), row.bodySha256())
                || !row.messageId().equals(NotificationWireCodec.uuidFromKey(row.publicationKey()))) {
            throw new InvalidContract("Notification Outbox digests or message identity are invalid");
        }
        Map<String, String> properties = NotificationWireCodec.envelope(
                NotificationEventContract.WORKFLOW, row.tenantId(), row.messageId(),
                row.businessIntentKey(), row.publicationKey(), row.aggregateId(),
                row.correlationId(), row.causationId(), row.createdAt(), body);
        return new WorkflowNotificationOutboxMessage(
                NotificationEventContract.WORKFLOW.topic(), row.messageId().toString(), body, properties);
    }

    /**
     * 持久化消息不满足冻结契约时的异常，阻止不兼容信封继续发送。
     */
    static final class InvalidContract extends RuntimeException {
        /**
         * 创建异常或专用实现并保留传入原因，供调用方分类处理。
         */
        InvalidContract(String message) { super(message); }
    }
}
