package com.flowdesk.common.notification;

import java.time.Duration;
import java.util.List;

/**
 * 集中定义两类通知请求的消息绑定，避免生产者和消费者部署后出现消息契约偏差。
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

    /**
     * 禁止直接实例化该工具类型，统一通过静态方法或工厂入口使用。
     */
    private NotificationEventContract() {
    }

    /**
     * 通知事件来源枚举，区分业务生产者领域，避免不同来源混用消费绑定。
     */
    public enum Source {
        TICKET,
        WORKFLOW
    }

    /**
     * 保存通知来源的主题、消费组及死信接收配置，并派生重试和死信主题。
     */
    public record Binding(Source source, String eventType, String contractRef,
                          String topic, String producerService, String producerGroup,
                          String consumerGroup, String dlqAcceptorGroup,
                          String intentNamespace) {
        /**
         * 按业务消费组生成 RocketMQ 死信主题，供恢复接收器绑定同一消息来源。
         */
        public String dlqTopic() {
            return "%DLQ%" + consumerGroup;
        }

        /**
         * 生成死信接收器自己的重试主题，区分业务消费失败与死信入库失败。
         */
        public String acceptorRetryTopic() {
            return "%RETRY%" + dlqAcceptorGroup;
        }

        /** 只有完整匹配持久化的路由身份才返回 true；恢复时不能只凭生产者名称选择消息绑定。 */
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

    /**
     * 返回工单与工作流两种通知来源的绑定，供统一配置主题、消费组及死信接收器。
     */
    public static Binding[] bindings() {
        return new Binding[]{TICKET, WORKFLOW};
    }
}
