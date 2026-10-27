package com.flowdesk.ticket.notification;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.notification.NotificationWireCodec;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** 从已持久化的工单通知字节构造固定契约信封，重试复用同一消息身份和内容。 */
final class TicketNotificationOutboxEnvelopeFactory {
    /**
     * 私有构造方法，阻止工具类实例化，调用方应使用静态入口。
     */
    private TicketNotificationOutboxEnvelopeFactory() { }
    /**
     * 校验工单通知创建请求并保存事实，按接口约定处理幂等及版本条件。
     */
    static TicketNotificationOutboxMessage create(com.flowdesk.ticket.model.notification.Claimed row) {
        if (!NotificationEventContract.TICKET.contractRef().equals(row.contractRef())
                || row.contractVersion() != NotificationEventContract.VERSION
                || !"NOTIFICATION_REQUEST".equals(row.aggregateType())) {
            throw new InvalidContract("Ticket Notification Outbox route is not frozen");
        }
        byte[] body = row.bodyBytes();
        if (body.length == 0 || body.length > NotificationEventContract.MAX_BODY_BYTES) throw new InvalidContract("Notification body size is invalid");
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body));
        } catch (Exception invalid) { throw new InvalidContract("Notification body is not UTF-8"); }
        if (row.businessIntentKey().length != 32 || row.publicationKey().length != 32
                || row.bodySha256().length != 32
                || !MessageDigest.isEqual(NotificationWireCodec.sha256(body), row.bodySha256())
                || !row.messageId().equals(NotificationWireCodec.uuidFromKey(row.publicationKey()))) {
            throw new InvalidContract("Notification Outbox digests or message identity are invalid");
        }
        Map<String, String> properties = NotificationWireCodec.envelope(
                NotificationEventContract.TICKET, row.tenantId(), row.messageId(), row.businessIntentKey(),
                row.publicationKey(), row.aggregateId(), row.correlationId(), row.causationId(), row.createdAt(), body);
        return new TicketNotificationOutboxMessage(NotificationEventContract.TICKET.topic(),
                row.messageId().toString(), body, properties);
    }
    /**
     * 持久化消息契约异常，发布器据此进入可诊断的恢复状态。
     */
    static final class InvalidContract extends RuntimeException {
        /**
         * 构造工单通知组件并保存调用依赖。
         */
        InvalidContract(String message) { super(message); } }
}
