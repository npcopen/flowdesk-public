package com.flowdesk.ticket.incident;

import java.time.Duration;
import java.util.List;

/** Ticket 持有的 M7 Incident 协作契约副本，固定消息主题、消费组和身份派生规则。 */
public final class IncidentMessageContract {
    public static final String ENVELOPE_VERSION = "1";
    public static final int VERSION = 1;
    public static final String AGGREGATE_TYPE = "INCIDENT";
    public static final int MAX_BODY_BYTES = 64 * 1024;
    public static final int MAX_RECONSUME_TIMES = 5;
    public static final int DELIVERY_ATTEMPTS = 6;
    public static final int DLQ_ACCEPTOR_MAX_RECONSUME_TIMES = 5;
    public static final int DLQ_ACCEPTOR_RETRY_DELAY_LEVEL = 1;
    public static final int OUTBOX_MAX_ATTEMPTS = 8;
    public static final int OUTBOX_MAX_CLAIMS = 50;
    public static final Duration OUTBOX_CLAIM_LEASE = Duration.ofSeconds(30);
    public static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);
    public static final int SDK_SEND_RETRIES = 0;
    public static final List<Duration> FAILURE_BACKOFFS = List.of(
            Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30),
            Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofMinutes(30),
            Duration.ofHours(2));

    /**
     * 私有构造方法，阻止工具类实例化，调用方应使用静态入口。
     */
    private IncidentMessageContract() { }

    /**
     * 消息绑定支持的业务种类，决定载荷校验及处理分支。
     */
    public enum Kind { CREATE_REQUESTED, RECOVERED }
    /**
     * 冻结消息路由与消费绑定，集中声明主题、消费组、类型和版本。
     */
    public record Binding(Kind kind, String eventType, String contractRef, String topic,
                          String consumerGroup, String dlqAcceptorGroup,
                          String intentNamespace) {
        /**
         * 返回冻结死信主题，供主消费重试耗尽后的恢复接收使用。
         */
        public String dlqTopic() { return "%DLQ%" + consumerGroup; }
        /**
         * 返回死信接收器专用重试主题，隔离主消费与接收器重试节奏。
         */
        public String acceptorRetryTopic() { return "%RETRY%" + dlqAcceptorGroup; }
    }

    public static final String ALERT_PRODUCER = "flowdesk-alert-service";
    public static final String TICKET_PRODUCER = "flowdesk-ticket-service";
    public static final Binding CREATE_REQUESTED = new Binding(
            Kind.CREATE_REQUESTED, "flowdesk.incident.ticket-create-requested",
            "flowdesk.incident.ticket-create-requested.v1",
            "flowdesk_incident_ticket_create_requested_v1",
            "flowdesk_incident_ticket_create_requested_v1_ticket_cg",
            "flowdesk_incident_ticket_create_requested_v1_ticket_dlq_acceptor_cg",
            "FD-INCIDENT-TICKET-CREATE-REQUESTED-V1");
    public static final Binding RECOVERED = new Binding(
            Kind.RECOVERED, "flowdesk.incident.recovered",
            "flowdesk.incident.recovered.v1", "flowdesk_incident_recovered_v1",
            "flowdesk_incident_recovered_v1_ticket_cg",
            "flowdesk_incident_recovered_v1_ticket_dlq_acceptor_cg",
            "FD-INCIDENT-RECOVERED-V1");

    public static final String ESTABLISHED_EVENT_TYPE = "flowdesk.incident.ticket-established";
    public static final String ESTABLISHED_CONTRACT_REF =
            "flowdesk.incident.ticket-established.v1";
    public static final String ESTABLISHED_TOPIC = "flowdesk_incident_ticket_established_v1";
    public static final String ESTABLISHED_PRODUCER_GROUP =
            "flowdesk_incident_ticket_established_v1_ticket_pg";
    public static final String ESTABLISHED_INTENT_NAMESPACE =
            "FD-INCIDENT-TICKET-ESTABLISHED-V1";
}
