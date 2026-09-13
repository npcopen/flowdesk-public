package com.flowdesk.common.notification;

import java.time.Duration;
import java.util.List;

/**
 * The two M8 notification request bindings.  Keeping the wire names in one
 * shared class prevents producer and consumer deployments from silently
 * drifting apart.
 */
public final class NotificationEventContract {

    public static final String ENVELOPE_VERSION = "1";
    public static final int VERSION = 1;
    public static final int MAX_BODY_BYTES = 64 * 1024;
    public static final int OUTBOX_MAX_ATTEMPTS = 8;
    public static final int OUTBOX_MAX_CLAIMS = 50;
    public static final int MAX_RECONSUME_TIMES = 5;
    public static final int DELIVERY_ATTEMPTS = 1 + MAX_RECONSUME_TIMES;
    public static final int DLQ_ACCEPTOR_MAX_RECONSUME_TIMES =
            DELIVERY_ATTEMPTS + MAX_RECONSUME_TIMES;
    public static final int DLQ_ACCEPTOR_RETRY_DELAY_LEVEL = 1;
    public static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration OUTBOX_CLAIM_LEASE = Duration.ofSeconds(30);
    public static final Duration EMPTY_POLL_INTERVAL = Duration.ofMillis(500);
    public static final int SDK_SEND_RETRIES = 0;
    public static final List<Duration> FAILURE_BACKOFFS = List.of(
            Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30),
            Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofMinutes(30),
            Duration.ofHours(2));

    private NotificationEventContract() {
    }

    public enum Source {
        TICKET,
        WORKFLOW
    }

    public record Binding(Source source, String eventType, String contractRef,
                          String topic, String producerService, String producerGroup,
                          String consumerGroup, String dlqAcceptorGroup,
                          String intentNamespace) {
        public String dlqTopic() {
            return "%DLQ%" + consumerGroup;
        }

        public String acceptorRetryTopic() {
            return "%RETRY%" + dlqAcceptorGroup;
        }

        /**
         * Returns true only for the complete persisted route identity.  A
         * producer name by itself is intentionally insufficient to select a
         * binding during recovery.
         */
        public boolean matchesRoute(String topic, String consumerGroup,
                                    String producerService, String contract,
                                    int contractVersion, String aggregateType) {
            return topic != null && consumerGroup != null && producerService != null
                    && contract != null && aggregateType != null
                    && topic.equals(topic())
                    && consumerGroup.equals(consumerGroup())
                    && producerService.equals(producerService())
                    && contract.equals(contractRef())
                    && contractVersion == VERSION
                    && aggregateType.equals("NOTIFICATION_REQUEST");
        }
    }

    public static final Binding TICKET = new Binding(
            Source.TICKET,
            "flowdesk.ticket.notification-requested",
            "flowdesk.ticket.notification-requested.v1",
            "flowdesk_ticket_notification_requested_v1",
            "flowdesk-ticket-service",
            "flowdesk_ticket_notification_requested_v1_ticket_pg",
            "flowdesk_ticket_notification_requested_v1_notification_cg",
            "flowdesk_ticket_notification_requested_v1_notification_dlq_acceptor_cg",
            "FD-TICKET-NOTIFICATION-REQUESTED-V1");

    public static final Binding WORKFLOW = new Binding(
            Source.WORKFLOW,
            "flowdesk.workflow.notification-requested",
            "flowdesk.workflow.notification-requested.v1",
            "flowdesk_workflow_notification_requested_v1",
            "flowdesk-workflow-service",
            "flowdesk_workflow_notification_requested_v1_workflow_pg",
            "flowdesk_workflow_notification_requested_v1_notification_cg",
            "flowdesk_workflow_notification_requested_v1_notification_dlq_acceptor_cg",
            "FD-WORKFLOW-NOTIFICATION-REQUESTED-V1");

    public static Binding[] bindings() {
        return new Binding[]{TICKET, WORKFLOW};
    }
}
