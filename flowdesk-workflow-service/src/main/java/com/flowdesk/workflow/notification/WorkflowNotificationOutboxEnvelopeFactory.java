package com.flowdesk.workflow.notification;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.notification.NotificationWireCodec;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Builds the frozen Notification envelope without reserializing persisted bytes. */
final class WorkflowNotificationOutboxEnvelopeFactory {
    private WorkflowNotificationOutboxEnvelopeFactory() { }

    static WorkflowNotificationOutboxMessage create(
            WorkflowNotificationOutboxRepository.Claimed row) {
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

    static final class InvalidContract extends RuntimeException {
        InvalidContract(String message) { super(message); }
    }
}
