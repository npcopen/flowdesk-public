package com.flowdesk.workflow.notification;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.notification.NotificationWireCodec;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * 工作流拥有的审批任务通知意图；通知身份同时绑定具体接收人和业务来源。
 */
public final class WorkflowNotificationIntent {
    public static final String SOURCE_SERVICE = "flowdesk-workflow-service";
    public static final NotificationEventContract.Binding BINDING = NotificationEventContract.WORKFLOW;
    public static final String APPROVAL_TASK_CREATED = "APPROVAL_TASK_CREATED";

    /**
     * 禁止直接实例化工具容器，当前类型通过静态方法提供共享规则和转换。
     */
    private WorkflowNotificationIntent() { }

    /**
     * 为指定任务与接收人构建稳定通知意图，保存原始载荷与去重身份供事务写入。
     */
    public static Intent create(UUID tenantId, UUID recipientUserId, UUID taskId,
                                 UUID workflowInstanceId, int levelNo,
                                 UUID correlationId, Instant occurredAt) {
        return create(tenantId, recipientUserId, taskId, workflowInstanceId, levelNo,
                correlationId, workflowInstanceId, occurredAt);
    }

    /**
     * 为指定任务与接收人构建稳定通知意图，保存原始载荷与去重身份供事务写入。
     */
    public static Intent create(UUID tenantId, UUID recipientUserId, UUID taskId,
                                UUID workflowInstanceId, int levelNo,
                                UUID correlationId, UUID causationId, Instant occurredAt) {
        return createForSource(tenantId, recipientUserId, taskId, workflowInstanceId, levelNo,
                correlationId, causationId, occurredAt, null);
    }

    /**
     * 为审批人交接生成通知意图，关联原任务和本次交接身份以避免重复提醒。
     */
    public static Intent reassigned(UUID tenantId, UUID recipientUserId, UUID taskId,
                                     UUID instanceId, int levelNo, UUID changeId, UUID correlationId, Instant now) {
        return createForSource(tenantId, recipientUserId, taskId, instanceId, levelNo,
                correlationId, changeId, now, changeId);
    }

    /**
     * 根据来源任务、接收人与业务效果构建通知意图，固定原始字节和去重身份。
     */
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

    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(Arrays.copyOf(value, 16));
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /**
     * 工作流不可变数据快照，组合声明字段供当前业务处理阶段传递完整事实。
     */
    public record Intent(UUID outboxId, UUID messageId, byte[] businessIntentKey,
                         byte[] publicationKey, byte[] sourceEffectKey, UUID sourceRefId,
                         UUID recipientUserId, byte[] bodyBytes, byte[] bodySha256,
                         UUID correlationId, UUID causationId, Instant occurredAt) {
        /**
         * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
         */
        public Intent {
            businessIntentKey = businessIntentKey.clone(); publicationKey = publicationKey.clone();
            sourceEffectKey = sourceEffectKey.clone(); bodyBytes = bodyBytes.clone(); bodySha256 = bodySha256.clone();
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
         * 返回来源副作用键的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override public byte[] sourceEffectKey() { return sourceEffectKey.clone(); }
        /**
         * 返回原始消息字节的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override public byte[] bodyBytes() { return bodyBytes.clone(); }
        /**
         * 返回消息体摘要的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override public byte[] bodySha256() { return bodySha256.clone(); }
    }
}
