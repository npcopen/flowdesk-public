package com.flowdesk.workflow.notification;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.notification.NotificationWireCodec;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Workflow-owned, recipient-specific approval-task notification intent. */
public final class WorkflowNotificationIntent {
    public static final String SOURCE_SERVICE = "flowdesk-workflow-service";
    public static final NotificationEventContract.Binding BINDING = NotificationEventContract.WORKFLOW;
    public static final String APPROVAL_TASK_CREATED = "APPROVAL_TASK_CREATED";

    private WorkflowNotificationIntent() { }

    public static Intent create(UUID tenantId, UUID recipientUserId, UUID taskId,
                                 UUID workflowInstanceId, int levelNo,
                                 UUID correlationId, Instant occurredAt) {
        return create(tenantId, recipientUserId, taskId, workflowInstanceId, levelNo,
                correlationId, workflowInstanceId, occurredAt);
    }

    public static Intent create(UUID tenantId, UUID recipientUserId, UUID taskId,
                                UUID workflowInstanceId, int levelNo,
                                UUID correlationId, UUID causationId, Instant occurredAt) {
        return createForSource(tenantId, recipientUserId, taskId, workflowInstanceId, levelNo,
                correlationId, causationId, occurredAt, null);
    }

    public static Intent reassigned(UUID tenantId, UUID recipientUserId, UUID taskId,
                                     UUID instanceId, int levelNo, UUID changeId, UUID correlationId, Instant now) {
        return createForSource(tenantId, recipientUserId, taskId, instanceId, levelNo,
                correlationId, changeId, now, changeId);
    }

    private static Intent createForSource(UUID tenantId, UUID recipientUserId, UUID taskId,
                                           UUID workflowInstanceId, int levelNo, UUID correlationId,
                                           UUID causationId, Instant occurredAt, UUID changeId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(recipientUserId, "recipientUserId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(workflowInstanceId, "workflowInstanceId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(causationId, "causationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        String kind = changeId == null ? APPROVAL_TASK_CREATED : "APPROVAL_TASK_REASSIGNED";
        byte[] source = changeId == null ? NotificationWireCodec.deriveKey(
                "FD-WORKFLOW-APPROVAL-TASK-SOURCE-V1", tenantId, taskId, levelNo)
                : NotificationWireCodec.deriveKey("FD-WORKFLOW-APPROVAL-HANDOFF-V1", tenantId, taskId, changeId);
        byte[] intent = NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-INTENT-V1", tenantId, source, recipientUserId,
                kind);
        byte[] publication = NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-PUBLICATION-V1", tenantId, source, recipientUserId,
                kind);
        NotificationWireCodec.Payload payload = new NotificationWireCodec.Payload(
                "1", recipientUserId, kind, NotificationWireCodec.hex(source),
                "APPROVAL_TASK", taskId, changeId == null ? "待处理审批" : "审批交接待办",
                changeId == null ? "你有一项新的审批任务。" : "一项审批任务已交接给你，请查看申请内容后办理。");
        byte[] body = NotificationWireCodec.encodePayload(payload);
        UUID messageId = uuid(publication);
        UUID outboxId = uuid(NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-OUTBOX-V1", tenantId, publication));
        return new Intent(outboxId, messageId, intent, publication, source, taskId,
                recipientUserId, body, NotificationWireCodec.sha256(body), correlationId,
                causationId, occurredAt);
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(Arrays.copyOf(value, 16));
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public record Intent(UUID outboxId, UUID messageId, byte[] businessIntentKey,
                         byte[] publicationKey, byte[] sourceEffectKey, UUID sourceRefId,
                         UUID recipientUserId, byte[] bodyBytes, byte[] bodySha256,
                         UUID correlationId, UUID causationId, Instant occurredAt) {
        public Intent {
            businessIntentKey = businessIntentKey.clone(); publicationKey = publicationKey.clone();
            sourceEffectKey = sourceEffectKey.clone(); bodyBytes = bodyBytes.clone(); bodySha256 = bodySha256.clone();
        }
        @Override public byte[] businessIntentKey() { return businessIntentKey.clone(); }
        @Override public byte[] publicationKey() { return publicationKey.clone(); }
        @Override public byte[] sourceEffectKey() { return sourceEffectKey.clone(); }
        @Override public byte[] bodyBytes() { return bodyBytes.clone(); }
        @Override public byte[] bodySha256() { return bodySha256.clone(); }
    }
}
