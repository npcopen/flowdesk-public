package com.flowdesk.alert.event;

import java.time.Duration;
import java.util.List;

/**
 * Alert 与 Ticket 双向协作的固定消息契约：Alert 发出建单请求、故障恢复事实，Ticket 返回工单关联结果。
 *
 * <p>主题、生产消费组和业务意图命名空间作为同一组绑定，供 Outbox 发布、信封校验和死信订阅共用，
 * 避免各处分别拼写导致投递方向或幂等身份不一致。投递次数、租约和退避时间也集中在这里保持一致。
 */
public final class AlertEventContract {
    public static final String ENVELOPE_VERSION = "1";
    public static final int VERSION = 1;
    public static final String SCHEMA_VERSION = "1";
    public static final String AGGREGATE_TYPE = "INCIDENT";
    public static final int MAX_BODY_BYTES = 64 * 1024;

    /** Outbox 每轮自动投递最多取得八次发送机会；SDK 自身不再叠加重试。 */
    public static final int MAX_ATTEMPTS = 8;

    public static final int MAX_CLAIMS = 50;

    /** 领取者在租约内有结算权；超时后允许其他实例接管，避免进程崩溃永久卡住消息。 */
    public static final Duration CLAIM_LEASE = Duration.ofSeconds(30);

    public static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);
    public static final int SDK_RETRIES = 0;

    /** 前七次失败后的间隔；第八次不再安排下一轮发送，而是转入恢复处理。 */
    public static final List<Duration> FAILURE_BACKOFFS =
            List.of(
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(2),
                    Duration.ofMinutes(10),
                    Duration.ofMinutes(30),
                    Duration.ofHours(2));

    /** 主队列首次投递加五次重投共六次；死信接纳器拥有独立的重投预算。 */
    public static final int MAX_RECONSUME_TIMES = 5;

    public static final int DELIVERY_ATTEMPTS = 6;
    public static final int DLQ_ACCEPTOR_MAX_RECONSUME_TIMES = 5;
    public static final int DLQ_ACCEPTOR_RETRY_DELAY_LEVEL = 1;

    /**
     * 禁止直接实例化该无状态工具类；调用方通过其静态方法或嵌套契约模型使用能力。
     */
    private AlertEventContract() {}

    /** 一个事件方向的路由、身份与幂等约定；死信主题属于主消费组，死信接纳组另有自己的重试主题。 */
    public record Binding(
            String eventType,
            String contractRef,
            String topic,
            String producer,
            String producerGroup,
            String consumerGroup,
            String dlqAcceptorGroup,
            String intentNamespace) {
        /**
         * 按冻结契约生成该消费组对应的死信 Topic，供重试耗尽后的接纳订阅使用。
         */
        public String dlqTopic() {
            return "%DLQ%" + consumerGroup;
        }

        /**
         * 生成死信接纳器专用重试 Topic，使接纳落库失败能够独立重投。
         */
        public String acceptorRetryTopic() {
            return "%RETRY%" + dlqAcceptorGroup;
        }
    }

    /** Alert 请求 Ticket 为指定故障建单，Ticket 侧负责幂等建立工单。 */
    public static final Binding TICKET_CREATE_REQUESTED =
            new Binding(
                    "flowdesk.incident.ticket-create-requested",
                    "flowdesk.incident.ticket-create-requested.v1",
                    "flowdesk_incident_ticket_create_requested_v1",
                    "flowdesk-alert-service",
                    "flowdesk_incident_ticket_create_requested_v1_alert_pg",
                    "flowdesk_incident_ticket_create_requested_v1_ticket_cg",
                    "flowdesk_incident_ticket_create_requested_v1_ticket_dlq_acceptor_cg",
                    "FD-INCIDENT-TICKET-CREATE-REQUESTED-V1");

    /** Ticket 回传已建立的权威 ticketId，Alert 据此完成故障关联并结束相关恢复记录。 */
    public static final Binding TICKET_ESTABLISHED =
            new Binding(
                    "flowdesk.incident.ticket-established",
                    "flowdesk.incident.ticket-established.v1",
                    "flowdesk_incident_ticket_established_v1",
                    "flowdesk-ticket-service",
                    "flowdesk_incident_ticket_established_v1_ticket_pg",
                    "flowdesk_incident_ticket_established_v1_alert_cg",
                    "flowdesk_incident_ticket_established_v1_alert_dlq_acceptor_cg",
                    "FD-INCIDENT-TICKET-ESTABLISHED-V1");

    /** Alert 发出故障已恢复的事实，供 Ticket 处理与故障关联的后续业务。 */
    public static final Binding INCIDENT_RECOVERED =
            new Binding(
                    "flowdesk.incident.recovered",
                    "flowdesk.incident.recovered.v1",
                    "flowdesk_incident_recovered_v1",
                    "flowdesk-alert-service",
                    "flowdesk_incident_recovered_v1_alert_pg",
                    "flowdesk_incident_recovered_v1_ticket_cg",
                    "flowdesk_incident_recovered_v1_ticket_dlq_acceptor_cg",
                    "FD-INCIDENT-RECOVERED-V1");
}
