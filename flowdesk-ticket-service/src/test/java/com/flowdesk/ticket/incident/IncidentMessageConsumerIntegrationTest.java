package com.flowdesk.ticket.incident;

import com.flowdesk.ticket.mapper.*;
import com.flowdesk.ticket.model.*;
import com.flowdesk.ticket.persistence.Uuids;

import com.flowdesk.ticket.persistence.Uuids;
import com.flowdesk.ticket.service.IdempotencyCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单模块回归测试，覆盖该组件的正常流程、边界条件与关键一致性约束。
 */
@SpringBootTest(properties={
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ticket-incident;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
        "spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:schema-test.sql",
        "flowdesk.ticket.incident-entry.enabled=true","flowdesk.ticket.incident-consumer.enabled=false",
        "flowdesk.ticket.workflow-reverse.entry.enabled=false","FLOWDESK_TICKET_DB_PASSWORD="
})
class IncidentMessageConsumerIntegrationTest {
    private static final UUID TENANT=UUID.fromString("21111111-1111-4111-8111-111111111111");
    private static final UUID POLICY=UUID.fromString("22222222-2222-4222-8222-222222222222");
    @Autowired IncidentMessageConsumer consumer;@Autowired IncidentDlqAcceptor dlq;
    @Autowired JdbcTemplate jdbc;
    /**
     * 注册隔离测试数据库、密钥和功能开关，使场景在可控环境运行。
     */
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("flowdesk.security.jwt.public-key-pem",()->resource("test-jwt-public-key.pem"));r.add("flowdesk.security.cursor-token.hmac-key",()->Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));}
    /**
     * 清理上次场景的持久化与替身状态，再建立本场景所需基础数据。
     */
    @BeforeEach void reset(){for(String table:new String[]{"message_recovery","audit_record","inbox_message","outbox_event","sla_clock","incident_ticket_mapping","ticket_timeline_entry","workflow_start_link","ticket","ticket_number_counter","service_catalog_item","sla_policy"})jdbc.execute("DELETE FROM "+table);Instant now=Instant.parse("2026-08-26T00:00:00Z");jdbc.update("INSERT INTO sla_policy(tenant_id,sla_policy_id,policy_code,policy_name,first_response_seconds,resolution_seconds,first_response_reminder_before_seconds,resolution_reminder_before_seconds,is_incident_default,status,lock_version,created_at,created_by_type,created_by_id,updated_at,updated_by_type,updated_by_id) VALUES(?,?,'INCIDENT','Incident',600,3600,60,300,1,'ACTIVE',0,?,'SERVICE',NULL,?,'SERVICE',NULL)",Uuids.toBytes(TENANT),Uuids.toBytes(POLICY),Timestamp.from(now),Timestamp.from(now));}

    /**
     * 验证告警建单原子保存权威映射、时间线、审计及两个 SLA 时钟。
     */
    @Test void createsAuthoritativeMappingTimelineAuditAndTwoClocksAtomically(){UUID incident=UUID.randomUUID();var create=create(incident,UUID.randomUUID());assertThat(consumer.consume(create).disposition()).isEqualTo(IncidentConsumeResult.Disposition.ACK);assertThat(count("ticket")).isEqualTo(1);assertThat(count("incident_ticket_mapping")).isEqualTo(1);assertThat(count("ticket_timeline_entry")).isEqualTo(1);assertThat(count("sla_clock")).isEqualTo(2);assertThat(count("audit_record")).isEqualTo(1);assertThat(count("outbox_event")).isEqualTo(1);assertThat(count("inbox_message")).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT status FROM ticket",String.class)).isEqualTo("PENDING_ASSIGNMENT");
        assertThat(consumer.consume(create).disposition()).isEqualTo(IncidentConsumeResult.Disposition.ACK);assertThat(count("ticket")).isEqualTo(1);assertThat(count("sla_clock")).isEqualTo(2);assertThat(count("outbox_event")).isEqualTo(1);}

    /**
     * 验证映射前到达的恢复消息可重试收敛，仅追加一次效果且不改变工单状态。
     */
    @Test void retriesRecoveryBeforeMappingThenAppendsExactlyOnceWithoutTicketStateChange(){UUID incident=UUID.randomUUID();var early=recovered(incident,UUID.randomUUID(),UUID.randomUUID());assertThat(consumer.consume(early).disposition()).isEqualTo(IncidentConsumeResult.Disposition.RETRY);assertThat(count("inbox_message")).isZero();var create=create(incident,UUID.randomUUID());assertThat(consumer.consume(create).disposition()).isEqualTo(IncidentConsumeResult.Disposition.ACK);var recovery=recovered(incident,UUID.randomUUID(),UUID.randomUUID());assertThat(consumer.consume(recovery).disposition()).isEqualTo(IncidentConsumeResult.Disposition.ACK);assertThat(count("ticket_timeline_entry")).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT status FROM ticket",String.class)).isEqualTo("PENDING_ASSIGNMENT");assertThat(consumer.consume(recovery).disposition()).isEqualTo(IncidentConsumeResult.Disposition.ACK);assertThat(count("ticket_timeline_entry")).isEqualTo(2);assertThat(count("ticket")).isEqualTo(1);}

    /**
     * 验证错误生产者消息在产生租户副作用之前作为毒消息确认。
     */
    @Test void wrongProducerIsPoisonAckedBeforeTenantEffects(){UUID incident=UUID.randomUUID();var good=create(incident,UUID.randomUUID());Map<String,String> properties=new LinkedHashMap<>(good.userProperties());properties.put("fd_producer","flowdesk-workflow-service");var bad=new IncidentTransportMessage(good.topic(),good.consumerGroup(),good.keys(),good.tags(),good.body(),properties);assertThat(consumer.consume(bad).disposition()).isEqualTo(IncidentConsumeResult.Disposition.ACK);assertThat(count("ticket")).isZero();assertThat(count("inbox_message")).isZero();}

    /**
     * 验证 v1 建单消息兼容新增可选载荷字段。
     */
    @Test void v1CreateIgnoresAnAdditiveOptionalPayloadField(){UUID incident=UUID.randomUUID();var message=create(incident,UUID.randomUUID(),",\"futureOptional\":{\"enabled\":true}");assertThat(consumer.consume(message).disposition()).isEqualTo(IncidentConsumeResult.Disposition.ACK);assertThat(count("ticket")).isEqualTo(1);assertThat(count("incident_ticket_mapping")).isEqualTo(1);}

    /**
     * 验证死信接收原子保存可恢复 Inbox、恢复项和审计。
     */
    @Test void dlqPersistsRecoverableInboxRecoveryAndAuditAtomically(){UUID incident=UUID.randomUUID();var original=create(incident,UUID.randomUUID());var dead=new IncidentTransportMessage(IncidentMessageContract.CREATE_REQUESTED.dlqTopic(),IncidentMessageContract.CREATE_REQUESTED.dlqAcceptorGroup(),original.keys(),original.tags(),original.body(),original.userProperties());assertThat(dlq.accept(dead).disposition()).isEqualTo(IncidentConsumeResult.Disposition.ACK);assertThat(count("inbox_message")).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT status FROM inbox_message",String.class)).isEqualTo("RECOVERY_REQUIRED");assertThat(jdbc.queryForObject("SELECT attempt_count FROM inbox_message",Integer.class)).isEqualTo(IncidentMessageContract.DELIVERY_ATTEMPTS);assertThat(count("message_recovery")).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT state FROM message_recovery",String.class)).isEqualTo("OPEN");assertThat(count("audit_record")).isEqualTo(1);}

    /**
     * 构造符合告警建单协议的测试消息，允许附加字段以覆盖 v1 向前兼容场景。
     */
    private IncidentTransportMessage create(UUID incident,UUID message){return create(incident,message,"");}
    /**
     * 构造符合告警建单协议的测试消息，允许附加字段以覆盖 v1 向前兼容场景。
     */
    private IncidentTransportMessage create(UUID incident,UUID message,String additiveFields){byte[] createIntent=IdempotencyCodec.derivedKey(IncidentMessageContract.CREATE_REQUESTED.intentNamespace(),TENANT,incident);String body="{\"schemaVersion\":\"1\",\"incidentId\":\""+incident+"\",\"createTicketIntentKey\":\""+HexFormat.of().formatHex(createIntent)+"\",\"fingerprint\":\"host=test\",\"generationNo\":1,\"cycleRef\":\"cycle-1\",\"summary\":\"CPU high\",\"openedAt\":\"2026-08-26T01:00:00.000000Z\""+additiveFields+"}";return wire(IncidentMessageContract.CREATE_REQUESTED,incident,message,createIntent,body);}
    /**
     * 构造告警恢复消息，供验证恢复先到、重复和关联收敛场景。
     */
    private IncidentTransportMessage recovered(UUID incident,UUID message,UUID recoveryEvent){byte[] createIntent=IdempotencyCodec.derivedKey(IncidentMessageContract.CREATE_REQUESTED.intentNamespace(),TENANT,incident);byte[] intent=IdempotencyCodec.derivedKey(IncidentMessageContract.RECOVERED.intentNamespace(),TENANT,incident);String body="{\"schemaVersion\":\"1\",\"incidentId\":\""+incident+"\",\"createTicketIntentKey\":\""+HexFormat.of().formatHex(createIntent)+"\",\"recoveryEventId\":\""+recoveryEvent+"\",\"recoveredAt\":\"2026-08-26T02:00:00.000000Z\"}";return wire(IncidentMessageContract.RECOVERED,incident,message,intent,body);}
    /**
     * 按契约构造传输消息，保留测试控制的原文字节、身份和路由。
     */
    private IncidentTransportMessage wire(IncidentMessageContract.Binding binding,UUID incident,UUID message,byte[] intent,String body){byte[] bytes=body.getBytes(StandardCharsets.UTF_8),hash=sha(bytes);Map<String,String> p=new LinkedHashMap<>();p.put("fd_envelope_version","1");p.put("fd_event_type",binding.eventType());p.put("fd_contract_ref",binding.contractRef());p.put("fd_contract_version","1");p.put("fd_producer",IncidentMessageContract.ALERT_PRODUCER);p.put("fd_tenant_id",TENANT.toString());p.put("fd_message_id",message.toString());p.put("fd_business_intent_key",HexFormat.of().formatHex(intent));p.put("fd_aggregate_type","INCIDENT");p.put("fd_aggregate_id",incident.toString());p.put("fd_correlation_id",UUID.randomUUID().toString());p.put("fd_occurred_at","2026-08-26T01:00:00.000000Z");p.put("fd_body_sha256",HexFormat.of().formatHex(hash));return new IncidentTransportMessage(binding.topic(),binding.consumerGroup(),message.toString(),null,bytes,p);}
    /**
     * 统计指定表中的测试记录，供断言效果数量和幂等性。
     */
    private int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    /**
     * 计算输入内容 SHA-256 摘要，供原文完整性和幂等内容核验。
     */
    private static byte[] sha(byte[] b){try{return java.security.MessageDigest.getInstance("SHA-256").digest(b);}catch(Exception e){throw new IllegalStateException(e);}}
    /**
     * 读取测试资源，缺失时中止场景以避免不完整密钥或夹具。
     */
    private static String resource(String name){try(var in=IncidentMessageConsumerIntegrationTest.class.getClassLoader().getResourceAsStream(name)){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException(e);}}
}
