package com.flowdesk.workflow.notification;

import java.util.Objects;
import java.util.UUID;

/** Writes one Workflow -> Notification intent in the enclosing business transaction. */
public final class WorkflowNotificationOutboxWriter {
    private final Persistence persistence;

    public WorkflowNotificationOutboxWriter(Persistence persistence) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
    }

    public void write(UUID tenantId, WorkflowNotificationIntent.Intent intent) {
        persistence.insert(Objects.requireNonNull(tenantId, "tenantId"),
                Objects.requireNonNull(intent, "intent"));
    }

    @FunctionalInterface
    public interface Persistence {
        void insert(UUID tenantId, WorkflowNotificationIntent.Intent intent);
    }
}
