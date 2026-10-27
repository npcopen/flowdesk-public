package com.flowdesk.ticket.reverse;

import com.flowdesk.ticket.mapper.*;
import com.flowdesk.ticket.model.*;
import com.flowdesk.ticket.persistence.Uuids;

import com.flowdesk.ticket.event.TicketEventContract;
import com.flowdesk.ticket.persistence.Uuids;
import com.flowdesk.ticket.service.IdempotencyCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单模块回归测试，覆盖该组件的正常流程、边界条件与关键一致性约束。
 */
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "flowdesk.ticket.workflow-reverse.entry.enabled=true",
        "spring.cloud.service-registry.auto-registration.enabled=false"
})
@Import(WorkflowReverseConsumerIntegrationTest.FixedTicketClockConfiguration.class)
class WorkflowReverseConsumerIntegrationTest {
    /**
     * 验证拒绝原因进入申请人时间线，并保持重复消费安全。
     */
    @Test
    void rejectionReasonReachesTheApplicantTimelineAndRemainsDuplicateSafe() {
        consumer.consume(established(UUID.randomUUID(),"RUNNING"));
        String reason="请补充访问用途，不能使用共享账号。";
        var source=finalized(UUID.randomUUID(),"REJECTED");
        String original=new String(source.body(),StandardCharsets.UTF_8);
        byte[] body=(original.substring(0,original.length()-1)+",\"decisionReason\":\""+reason+"\"}").getBytes(StandardCharsets.UTF_8);
        var message=wire(WorkflowReverseContract.APPROVAL_FINALIZED,UUID.randomUUID(),body,
                IdempotencyCodec.derivedKey(WorkflowReverseContract.APPROVAL_FINALIZED.intentNamespace(),TENANT,INSTANCE));
        assertThat(consumer.consume(message).code()).isEqualTo("WORKFLOW_REVERSE_PROCESSED");
        consumer.consume(message);
        assertThat(text("SELECT summary FROM ticket_timeline_entry WHERE event_type='APPROVAL_REJECTED'")).isEqualTo(reason);
        assertThat(text("SELECT status FROM ticket")).isEqualTo("APPROVAL_REJECTED");
    }
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TICKET = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID REQUESTER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    private static final UUID CATALOG = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
    private static final UUID POLICY = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000004");
    private static final UUID DEFINITION = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000005");
    private static final UUID VERSION = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000006");
    private static final UUID INSTANCE = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000007");
    private static final UUID START_MESSAGE = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000008");
    private static final UUID CORRELATION = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000009");
    private static final Instant REQUESTED = Instant.parse("2026-08-24T10:00:00.000000Z");
    private static final Instant STARTED = Instant.parse("2026-08-24T10:00:01.000000Z");
    private static final Instant COMPLETED = Instant.parse("2026-08-24T10:00:05.000000Z");
    private static final Instant TICKET_ACCEPTED = Instant.parse("2026-08-24T10:00:07.000000Z");

    @Autowired WorkflowReverseConsumer consumer;
    @Autowired WorkflowReverseDlqAcceptor dlq;
    @Autowired JdbcTemplate jdbc;

    /**
     * 注册隔离测试数据库、密钥和功能开关，使场景在可控环境运行。
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("FLOWDESK_TICKET_DB_PASSWORD", () -> "");
        registry.add("flowdesk.security.cursor-token.hmac-key", () ->
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:ticket-reverse;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:schema-test.sql");
    }

    /**
     * 保存本场景前置业务事实，使用参数控制租户、身份、状态及时间边界。
     */
    @BeforeEach
    void seed() {
        for (String table : List.of("message_recovery", "inbox_message", "audit_record",
                "sla_clock", "ticket_timeline_entry", "workflow_start_link", "ticket",
                "service_catalog_item", "sla_policy")) jdbc.update("DELETE FROM " + table);
        jdbc.update("""
                INSERT INTO sla_policy
                  (tenant_id,sla_policy_id,policy_code,policy_name,first_response_seconds,
                   resolution_seconds,first_response_reminder_before_seconds,
                   resolution_reminder_before_seconds,is_incident_default,status,
                   created_by_type,updated_by_type)
                VALUES (?,?,'P1','P1',60,300,10,30,0,'ACTIVE','SERVICE','SERVICE')
                """, Uuids.toBytes(TENANT), Uuids.toBytes(POLICY));
        jdbc.update("""
                INSERT INTO service_catalog_item
                  (tenant_id,catalog_item_id,item_code,item_name,risk_level,requires_approval,
                   workflow_definition_id,workflow_version_id,workflow_version_no,sla_policy_id,
                   status,created_by_type,updated_by_type)
                VALUES (?,?,'HIGH','High','HIGH',TRUE,?,?,1,?,'ACTIVE','SERVICE','SERVICE')
                """, Uuids.toBytes(TENANT), Uuids.toBytes(CATALOG), Uuids.toBytes(DEFINITION),
                Uuids.toBytes(VERSION), Uuids.toBytes(POLICY));
        jdbc.update("""
                INSERT INTO ticket
                  (tenant_id,ticket_id,ticket_no,creation_intent_key,origin_type,catalog_item_id,
                   requester_user_id,subject,description,status,workflow_definition_id,
                   workflow_version_id,workflow_version_no,created_by_type,created_by_id,
                   updated_by_type,updated_by_id)
                VALUES (?,?,'FD-1',?,'MANUAL',?,?,'s','d','AWAITING_APPROVAL',?,?,1,
                        'USER',?,'USER',?)
                """, Uuids.toBytes(TENANT), Uuids.toBytes(TICKET), new byte[32],
                Uuids.toBytes(CATALOG), Uuids.toBytes(REQUESTER), Uuids.toBytes(DEFINITION),
                Uuids.toBytes(VERSION), Uuids.toBytes(REQUESTER), Uuids.toBytes(REQUESTER));
        jdbc.update("""
                INSERT INTO workflow_start_link
                  (tenant_id,ticket_id,start_intent_key,status,requested_at,created_by_type,
                   updated_by_type)
                VALUES (?,?,?,'REQUESTED',?,'SERVICE','SERVICE')
                """, Uuids.toBytes(TENANT), Uuids.toBytes(TICKET), startIntent(),
                Timestamp.from(REQUESTED));
    }

    /**
     * 验证实例建立后审批通过原子提交，重复消息不重复效果。
     */
    @Test
    void establishedThenPassedIsAtomicAndDuplicateSafe() {
        assertThat(consumer.consume(established(UUID.randomUUID(), "RUNNING")).code())
                .isEqualTo("WORKFLOW_REVERSE_PROCESSED");
        assertThat(text("SELECT status FROM workflow_start_link")).isEqualTo("ESTABLISHED");
        assertThat(text("SELECT status FROM ticket")).isEqualTo("AWAITING_APPROVAL");

        WorkflowReverseTransportMessage finalized = finalized(UUID.randomUUID(), "PASSED");
        assertThat(consumer.consume(finalized).disposition())
                .isEqualTo(WorkflowReverseConsumeResult.Disposition.ACK);
        assertThat(text("SELECT status FROM ticket")).isEqualTo("PENDING_ASSIGNMENT");
        assertThat(count("sla_clock")).isEqualTo(2);
        assertThat(count("ticket_timeline_entry")).isEqualTo(1);
        assertThat(count("inbox_message")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT MIN(started_at) FROM sla_clock", Timestamp.class)
                .toInstant()).isEqualTo(TICKET_ACCEPTED);

        assertThat(consumer.consume(finalized).code()).isEqualTo("WORKFLOW_REVERSE_DUPLICATE");
        assertThat(count("sla_clock")).isEqualTo(2);
        assertThat(count("ticket_timeline_entry")).isEqualTo(1);
    }

    /**
     * 验证终态先到仍可收敛，相反结论不能覆盖既有结果。
     */
    @Test
    void finalizedBeforeEstablishedConvergesAndOppositeIntentCannotOverwrite() {
        assertThat(consumer.consume(finalized(UUID.randomUUID(), "REJECTED")).code())
                .isEqualTo("WORKFLOW_REVERSE_PROCESSED");
        assertThat(text("SELECT status FROM ticket")).isEqualTo("APPROVAL_REJECTED");
        assertThat(text("SELECT status FROM workflow_start_link")).isEqualTo("ESTABLISHED");

        assertThat(consumer.consume(established(UUID.randomUUID(), "COMPLETED_PASSED")).code())
                .isEqualTo("WORKFLOW_REVERSE_PROCESSED");
        assertThat(text("SELECT status FROM ticket")).isEqualTo("APPROVAL_REJECTED");
        assertThat(count("inbox_message")).isEqualTo(2);
        assertThat(count("sla_clock")).isZero();

        WorkflowReverseTransportMessage opposite = finalized(UUID.randomUUID(), "PASSED");
        assertThat(consumer.consume(opposite).code())
                .isEqualTo("BUSINESS_INTENT_CONTENT_CONFLICT");
        assertThat(text("SELECT status FROM ticket")).isEqualTo("APPROVAL_REJECTED");
        assertThat(count("inbox_message")).isEqualTo(2);
    }

    /**
     * 验证死信仅建立一个可恢复 Inbox，不产生工单业务副作用。
     */
    @Test
    void dlqCreatesOneRecoverableInboxWithoutBusinessEffects() {
        WorkflowReverseTransportMessage main = established(UUID.randomUUID(), "RUNNING");
        WorkflowReverseTransportMessage dead = new WorkflowReverseTransportMessage(
                WorkflowReverseContract.INSTANCE_ESTABLISHED.dlqTopic(),
                WorkflowReverseContract.INSTANCE_ESTABLISHED.dlqAcceptorGroup(),
                main.keys(), main.tags(), main.body(), main.userProperties());
        assertThat(dlq.accept(dead).code()).isEqualTo("WORKFLOW_REVERSE_RECOVERY_REQUIRED");
        assertThat(text("SELECT status FROM inbox_message")).isEqualTo("RECOVERY_REQUIRED");
        assertThat(text("SELECT state FROM message_recovery")).isEqualTo("OPEN");
        assertThat(text("SELECT status FROM workflow_start_link")).isEqualTo("REQUESTED");
        assertThat(dlq.accept(dead).code()).isEqualTo("WORKFLOW_REVERSE_DLQ_DUPLICATE");
        assertThat(count("message_recovery")).isEqualTo(1);
    }

    /**
     * 构造实例建立反向消息，供验证启动关联与后续终态处理。
     */
    private WorkflowReverseTransportMessage established(UUID messageId, String status) {
        byte[] body = ("{\"schemaVersion\":\"1\",\"ticketId\":\"" + TICKET
                + "\",\"workflowInstanceId\":\"" + INSTANCE
                + "\",\"startIntentKey\":\"" + HexFormat.of().formatHex(startIntent())
                + "\",\"workflowDefinitionId\":\"" + DEFINITION
                + "\",\"workflowVersionId\":\"" + VERSION
                + "\",\"versionNo\":1,\"instanceStatus\":\"" + status
                + "\",\"startedAt\":\"2026-08-24T10:00:01.000000Z\"}")
                .getBytes(StandardCharsets.UTF_8);
        return wire(WorkflowReverseContract.INSTANCE_ESTABLISHED, messageId, body,
                IdempotencyCodec.derivedKey(
                        WorkflowReverseContract.INSTANCE_ESTABLISHED.intentNamespace(), TENANT, TICKET));
    }

    /**
     * 构造审批终态反向消息，控制结论与顺序以覆盖重复和冲突。
     */
    private WorkflowReverseTransportMessage finalized(UUID messageId, String outcome) {
        byte[] body = ("{\"schemaVersion\":\"1\",\"ticketId\":\"" + TICKET
                + "\",\"workflowInstanceId\":\"" + INSTANCE
                + "\",\"startIntentKey\":\"" + HexFormat.of().formatHex(startIntent())
                + "\",\"workflowDefinitionId\":\"" + DEFINITION
                + "\",\"workflowVersionId\":\"" + VERSION
                + "\",\"versionNo\":1,\"outcome\":\"" + outcome
                + "\",\"completedAt\":\"2026-08-24T10:00:05.000000Z\"}")
                .getBytes(StandardCharsets.UTF_8);
        return wire(WorkflowReverseContract.APPROVAL_FINALIZED, messageId, body,
                IdempotencyCodec.derivedKey(
                        WorkflowReverseContract.APPROVAL_FINALIZED.intentNamespace(), TENANT, INSTANCE));
    }

    /**
     * 按契约构造传输消息，保留测试控制的原文字节、身份和路由。
     */
    private WorkflowReverseTransportMessage wire(WorkflowReverseContract.Binding binding,
                                                  UUID messageId, byte[] body, byte[] intent) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("fd_envelope_version", "1"); p.put("fd_event_type", binding.eventType());
        p.put("fd_contract_ref", binding.contractRef()); p.put("fd_contract_version", "1");
        p.put("fd_producer", WorkflowReverseContract.PRODUCER);
        p.put("fd_tenant_id", TENANT.toString()); p.put("fd_message_id", messageId.toString());
        p.put("fd_business_intent_key", HexFormat.of().formatHex(intent));
        p.put("fd_aggregate_type", WorkflowReverseContract.AGGREGATE_TYPE);
        p.put("fd_aggregate_id", INSTANCE.toString());
        p.put("fd_correlation_id", CORRELATION.toString());
        p.put("fd_causation_id", START_MESSAGE.toString());
        p.put("fd_occurred_at", "2026-08-24T10:00:06.000000Z");
        p.put("fd_body_sha256", HexFormat.of().formatHex(sha256(body)));
        return new WorkflowReverseTransportMessage(binding.topic(), binding.consumerGroup(),
                messageId.toString(), null, body, p);
    }

    /**
     * 构造工作流启动的规范意图，使反向消息与原工单建立可核验关联。
     */
    private static byte[] startIntent() {
        return IdempotencyCodec.derivedKey(
                TicketEventContract.WorkflowStartRequested.BUSINESS_INTENT_KEY_NAMESPACE,
                TENANT, TICKET);
    }
    /**
     * 计算输入内容 SHA-256 摘要，供消息完整性和业务意图核验。
     */
    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    /**
     * 统计指定表中的测试记录，供断言效果数量和幂等性。
     */
    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
    /**
     * 执行单值查询并返回文本，供核对测试落库的原因或状态字段。
     */
    private String text(String sql) { return jdbc.queryForObject(sql, String.class); }

    /**
     * 用于该测试的内部替身，提供可控制的时钟、调度或依赖行为。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTicketClockConfiguration {
        /**
         * 装配工作流反向结果的 fixedTicketClock 组件，将配置与依赖交由 Spring 管理。
         */
        @Bean
        @Primary
        Clock fixedTicketClock() {
            return Clock.fixed(TICKET_ACCEPTED, ZoneOffset.UTC);
        }
    }
}
