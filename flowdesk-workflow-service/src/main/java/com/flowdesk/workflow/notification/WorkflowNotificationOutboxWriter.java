package com.flowdesk.workflow.notification;

import java.util.Objects;
import java.util.UUID;

/**
 * 在调用方的业务事务内写入一条 Workflow 到 Notification 的通知意图。
 */
public final class WorkflowNotificationOutboxWriter {
    private final Persistence persistence;

    /**
     * 注入最小持久化写入函数，使共享通知意图写入器能参与各业务入口已有事务。
     */
    public WorkflowNotificationOutboxWriter(Persistence persistence) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
    }

    /**
     * 在调用方本地事务中写入通知 Outbox，保持业务状态与通知意图原子提交。
     */
    public void write(UUID tenantId, WorkflowNotificationIntent.Intent intent) {
        persistence.insert(Objects.requireNonNull(tenantId, "tenantId"),
                Objects.requireNonNull(intent, "intent"));
    }

    /**
     * 通知意图的最小持久化边界，供共享写入器参与调用方已有事务。
     */
    @FunctionalInterface
    public interface Persistence {
        /**
         * 持久化指定通知意图，供共享写入器参与当前业务事务。
         */
        void insert(UUID tenantId, WorkflowNotificationIntent.Intent intent);
    }
}
