package com.flowdesk.alert.recovery;
import com.flowdesk.alert.service.AlertRecoveryExecutionService;
import com.flowdesk.alert.mapper.AlertPersistenceSupport;

import com.flowdesk.common.recovery.RecoveryReplayResumer;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.concurrent.atomic.AtomicReference;
import com.flowdesk.alert.event.AlertEventContract;
import com.flowdesk.alert.service.AlertIdempotencyCodec;
import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.RoleCode;
import com.flowdesk.common.security.JwtIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证告警人工恢复 API的行为约束；结合可控测试数据检查正常流程、失败边界或状态收敛，避免回归破坏既有契约。
 */
@SpringBootTest(properties={
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:alert-recovery;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
        "spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:schema-test.sql",
        "flowdesk.recovery.resume-enabled=false","flowdesk.alert.ticket-result.entry.enabled=true","flowdesk.alert.ticket-result.consumer.enabled=false","FLOWDESK_ALERT_DB_PASSWORD="
})
@AutoConfigureMockMvc
class AlertRecoveryApiIntegrationTest{
    private static final UUID TENANT=UUID.fromString("51111111-1111-4111-8111-111111111111"),ADMIN=UUID.fromString("52222222-2222-4222-8222-222222222222");private static final KeyPair KEY=newKeyPair();private static final JwtIssuer ISSUER=new JwtIssuer(KEY.getPrivate(),"flowdesk-iam-service","flowdesk-api","flowdesk-internal",900,300);
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;
    /**
     * 为集成测试注册独立数据库、验签公钥及组件开关，使测试上下文使用可控基础设施。
     */
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("flowdesk.security.jwt.public-key-pem",AlertRecoveryApiIntegrationTest::publicKeyPem);r.add("flowdesk.security.cursor-token.hmac-key",()->Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));}
    @Autowired private RecoveryReplayResumer resumer;
    @Autowired private com.flowdesk.alert.mapper.AlertRecoveryReplayMapper replayMapper;
    @Autowired private PlatformTransactionManager recoveryTransactions;
    @Autowired private AlertRecoveryExecutionService worker;

    /**
     * 验证进程丢失 Inbox 人工重放任务后能在租约到期接管，业务效果和人工尝试不重复。
     */
    @Test void lostInboxReplayClaimResumesAfterLeaseWithoutRepeatedEffect() {
        Instant at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        UUID recovery=seedReplayClaim("restart",RecoveryReplayResumer.leaseUntil(at),at);
        assertThat(resumer.resumeDue(at)).isZero();
        assertThat(resumer.resumeDue(at.plusSeconds(121))).isEqualTo(1);
        await("SELECT state FROM message_recovery WHERE recovery_id=?",AlertPersistenceSupport.bytes(recovery),"RESOLVED");
        assertThat(resumer.resumeDue(at.plusSeconds(600))).isZero();
        assertThat(jdbc.queryForObject("SELECT replay_count FROM message_recovery",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM incident_ticket_link",String.class)).isEqualTo("ESTABLISHED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_record WHERE action_code='INCIDENT_TICKET_LINK_ESTABLISHED'",Integer.class)).isEqualTo(1);
    }

    /**
     * 验证历史重放及中断后的接管可继续恢复，同时旧工作线程无法结算新租约。
     */
    @Test void legacyReplayAndInterruptedTakeoverAreResumedAgainWithStaleWorkerFenced() {
        Instant at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        UUID recovery=seedReplayClaim("legacy",null,at);
        AtomicReference<RecoveryReplayResumer.Task> lost=new AtomicReference<>();
        var interruptedProcess=new RecoveryReplayResumer(replayMapper,recoveryTransactions,lost::set,false);
        assertThat(interruptedProcess.resumeDue(at)).isEqualTo(1);
        assertThat(resumer.resumeDue(at.plusSeconds(1))).isZero();
        assertThat(resumer.resumeDue(at.plusSeconds(121))).isEqualTo(1);
        await("SELECT state FROM message_recovery WHERE recovery_id=?",AlertPersistenceSupport.bytes(recovery),"RESOLVED");
        var stale=lost.get();
        worker.execute(new AlertRecoveryService.Work(stale.tenantId(),stale.recoveryId(),stale.sourceRecordId(),
                stale.actorId(),stale.reason(),stale.attemptCorrelationId(),stale.sourceCorrelationId(),stale.leaseUntil()));
        assertThat(jdbc.queryForObject("SELECT state FROM message_recovery",String.class)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_record WHERE action_code='INCIDENT_TICKET_LINK_ESTABLISHED'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT replay_count FROM message_recovery",Integer.class)).isEqualTo(1);
    }

    /**
     * 植入带人工尝试及租约的 Inbox 重放快照，模拟已接纳任务丢失或旧执行者中断。
     */
    private UUID seedReplayClaim(String suffix,Instant lease,Instant at) {
        Seed seed=seedIncident(suffix,"REQUESTED",null);
        UUID ticket=UUID.randomUUID(),message=UUID.randomUUID(),inbox=UUID.randomUUID();
        byte[] intent=AlertIdempotencyCodec.derivedKey(AlertEventContract.TICKET_ESTABLISHED.intentNamespace(),TENANT,seed.incident());
        String payload="{\"schemaVersion\":\"1\",\"incidentId\":\""+seed.incident()+"\",\"createTicketIntentKey\":\""+HexFormat.of().formatHex(seed.createIntent())+"\",\"ticketId\":\""+ticket+"\",\"establishedAt\":\"2026-08-26T03:00:00.000000Z\"}";
        byte[] body=payload.getBytes(StandardCharsets.UTF_8);
        jdbc.update("""
                INSERT INTO inbox_message(tenant_id,inbox_id,producer_service,message_id,business_intent_key,contract_ref,
                    contract_version,body_sha256,body_bytes,correlation_id,causation_id,occurred_at,status,result_type,result_id,
                    attempt_count,last_error_code,first_received_at,last_attempt_at,processed_at,lock_version,created_at,
                    updated_at,updated_by_type,updated_by_id)
                VALUES(?,?,?,?,?,?,1,?,?,?,?,?,'RECOVERY_REQUIRED',NULL,NULL,6,'BROKER_RETRIES_EXHAUSTED',?,?,NULL,0,?,?,'SERVICE',NULL)
                """,AlertPersistenceSupport.bytes(TENANT),AlertPersistenceSupport.bytes(inbox),AlertEventContract.TICKET_ESTABLISHED.producer(),
                AlertPersistenceSupport.bytes(message),intent,AlertEventContract.TICKET_ESTABLISHED.contractRef(),
                AlertIdempotencyCodec.sha256(body),body,AlertPersistenceSupport.bytes(UUID.randomUUID()),null,Timestamp.from(at),
                Timestamp.from(at),Timestamp.from(at),Timestamp.from(at),Timestamp.from(at));
        UUID recovery=seedRecovery("INBOX",inbox,intent,6);
        jdbc.update("""
                UPDATE message_recovery SET state='REPLAYING',next_attempt_at=?,replay_count=1,last_replayed_at=?,
                    last_replayed_by_type='USER',last_replayed_by_id=?,last_replay_reason='verified original result',
                    last_replay_correlation_id=?,lock_version=lock_version+1 WHERE recovery_id=?
                """,lease==null?null:Timestamp.from(lease),Timestamp.from(at),AlertPersistenceSupport.bytes(ADMIN),
                AlertPersistenceSupport.bytes(UUID.randomUUID()),AlertPersistenceSupport.bytes(recovery));
        return recovery;
    }

    /**
     * 在测试前清理并重新建立所需数据库事实，避免前一个测试的消息状态或审计影响当前断言。
     */
    @BeforeEach void reset(){for(String table:new String[]{"message_recovery","audit_record","inbox_message","outbox_event","incident_ticket_link","incident","alert_event","webhook_nonce","monitoring_integration"})jdbc.execute("DELETE FROM "+table);}

    /**
     * 验证人工重放建单发件箱可恢复发布，恢复通知发件箱则不会把已建立工单关联降级。
     */
    @Test void replaysCreateOutboxButNeverDowngradesEstablishedLinkForRecoveryOutbox()throws Exception{Seed create=seedIncident("create","RECOVERY_REQUIRED",null);UUID createOutbox=seedOutbox(create,AlertEventContract.TICKET_CREATE_REQUESTED.contractRef());UUID createRecovery=seedRecovery("OUTBOX",createOutbox,create.createIntent(),8);String auth="Bearer "+token();mvc.perform(get("/api/v1/alert-message-recoveries/"+createRecovery).header("Authorization",auth)).andExpect(status().isOk()).andExpect(header().string("ETag","\"lv:0\""));mvc.perform(post("/api/v1/alert-message-recoveries/"+createRecovery+"/replays").header("Authorization",auth).header("Idempotency-Key","alert-replay-key-0001").header("If-Match","\"lv:0\"").contentType("application/json").content("{\"reason\":\"operator verified broker availability\"}")).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.state").value("REPLAYING")).andExpect(jsonPath("$.data.replayCount").value(1));assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE outbox_id=?",String.class,AlertPersistenceSupport.bytes(createOutbox))).isEqualTo("PENDING");assertThat(jdbc.queryForObject("SELECT status FROM incident_ticket_link WHERE incident_id=?",String.class,AlertPersistenceSupport.bytes(create.incident()))).isEqualTo("REQUESTED");
        UUID ticket=UUID.randomUUID();Seed recovered=seedIncident("recovered","ESTABLISHED",ticket);UUID recoveryOutbox=seedOutbox(recovered,AlertEventContract.INCIDENT_RECOVERED.contractRef());UUID recoveryFact=seedRecovery("OUTBOX",recoveryOutbox,AlertIdempotencyCodec.derivedKey(AlertEventContract.INCIDENT_RECOVERED.intentNamespace(),TENANT,recovered.incident()),8);mvc.perform(post("/api/v1/alert-message-recoveries/"+recoveryFact+"/replays").header("Authorization",auth).header("Idempotency-Key","alert-replay-key-0002").header("If-Match","\"lv:0\"").contentType("application/json").content("{\"reason\":\"retry timeline delivery only\"}")).andExpect(status().isAccepted());assertThat(jdbc.queryForObject("SELECT status FROM incident_ticket_link WHERE incident_id=?",String.class,AlertPersistenceSupport.bytes(recovered.incident()))).isEqualTo("ESTABLISHED");assertThat(AlertPersistenceSupport.uuid(jdbc.queryForObject("SELECT ticket_id FROM incident_ticket_link WHERE incident_id=?",byte[].class,AlertPersistenceSupport.bytes(recovered.incident())))).isEqualTo(ticket);}

    /**
     * 验证受控 Inbox 人工重放复用原权威结果入口并结束恢复记录，避免引入第二套关联逻辑。
     */
    @Test void controlledInboxReplayUsesSameAuthoritativeResultEntryAndResolves()throws Exception{Seed seed=seedIncident("inbox","REQUESTED",null);UUID ticket=UUID.randomUUID(),message=UUID.randomUUID();byte[] resultIntent=AlertIdempotencyCodec.derivedKey(AlertEventContract.TICKET_ESTABLISHED.intentNamespace(),TENANT,seed.incident());String body="{\"schemaVersion\":\"1\",\"incidentId\":\""+seed.incident()+"\",\"createTicketIntentKey\":\""+HexFormat.of().formatHex(seed.createIntent())+"\",\"ticketId\":\""+ticket+"\",\"establishedAt\":\"2026-08-26T03:00:00.000000Z\"}";byte[] bytes=body.getBytes(StandardCharsets.UTF_8);UUID inbox=UUID.randomUUID();Instant now=Instant.parse("2026-08-26T03:00:00Z");jdbc.update("INSERT INTO inbox_message(tenant_id,inbox_id,producer_service,message_id,business_intent_key,contract_ref,contract_version,body_sha256,body_bytes,correlation_id,causation_id,occurred_at,status,result_type,result_id,attempt_count,last_error_code,first_received_at,last_attempt_at,processed_at,lock_version,created_at,updated_at,updated_by_type,updated_by_id) VALUES(?,?,?,?,?,?,1,?,?,?,?,?,'RECOVERY_REQUIRED',NULL,NULL,6,'BROKER_RETRIES_EXHAUSTED',?,?,NULL,0,?,?,'SERVICE',NULL)",AlertPersistenceSupport.bytes(TENANT),AlertPersistenceSupport.bytes(inbox),AlertEventContract.TICKET_ESTABLISHED.producer(),AlertPersistenceSupport.bytes(message),resultIntent,AlertEventContract.TICKET_ESTABLISHED.contractRef(),AlertIdempotencyCodec.sha256(bytes),bytes,AlertPersistenceSupport.bytes(UUID.randomUUID()),null,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));UUID recovery=seedRecovery("INBOX",inbox,resultIntent,6);mvc.perform(post("/api/v1/alert-message-recoveries/"+recovery+"/replays").header("Authorization","Bearer "+token()).header("Idempotency-Key","alert-replay-key-0003").header("If-Match","\"lv:0\"").contentType("application/json").content("{\"reason\":\"replay accepted Ticket result\"}")).andExpect(status().isAccepted());await("SELECT state FROM message_recovery WHERE recovery_id=?",AlertPersistenceSupport.bytes(recovery),"RESOLVED");assertThat(jdbc.queryForObject("SELECT status FROM inbox_message WHERE inbox_id=?",String.class,AlertPersistenceSupport.bytes(inbox))).isEqualTo("PROCESSED");assertThat(jdbc.queryForObject("SELECT status FROM incident_ticket_link WHERE incident_id=?",String.class,AlertPersistenceSupport.bytes(seed.incident()))).isEqualTo("ESTABLISHED");}

    /**
     * 插入当前场景所需的故障周期及必要关联事实，供消费者或人工恢复测试使用。
     */
    private Seed seedIncident(String suffix,String status,UUID ticket){Instant now=Instant.parse("2026-08-26T01:00:00Z");UUID integration=UUID.randomUUID(),event=UUID.randomUUID(),incident=UUID.randomUUID();byte[] create=AlertIdempotencyCodec.derivedKey(AlertEventContract.TICKET_CREATE_REQUESTED.intentNamespace(),TENANT,incident);jdbc.update("INSERT INTO alert_event(tenant_id,alert_event_id,integration_id,external_event_id,event_type,fingerprint,alert_cycle_ref,occurred_at,received_at,disposition,incident_id,raw_body_sha256,raw_body_ref,raw_body_ciphertext,summary,correlation_id,created_at,created_by_type,created_by_id) VALUES(?,?,?,?,'FIRING',?,'cycle',?,?,'APPLIED',?,?,NULL,NULL,'seed',?,?,'INTEGRATION',?)",AlertPersistenceSupport.bytes(TENANT),AlertPersistenceSupport.bytes(event),AlertPersistenceSupport.bytes(integration),"evt-"+suffix,"fp-"+suffix,Timestamp.from(now),Timestamp.from(now),AlertPersistenceSupport.bytes(incident),new byte[32],AlertPersistenceSupport.bytes(UUID.randomUUID()),Timestamp.from(now),AlertPersistenceSupport.bytes(integration));jdbc.update("INSERT INTO incident(tenant_id,incident_id,fingerprint,generation_no,status,first_event_id,last_event_id,event_count,opened_at,last_event_at,recovered_at,recovery_event_id,recovery_review_state,summary,lock_version,created_at,created_by_type,created_by_id,updated_at,updated_by_type,updated_by_id) VALUES(?,?,?,1,'ACTIVE',?,?,1,?,?,NULL,NULL,'NONE','seed',0,?,'INTEGRATION',?,?,'SERVICE',NULL)",AlertPersistenceSupport.bytes(TENANT),AlertPersistenceSupport.bytes(incident),"fp-"+suffix,AlertPersistenceSupport.bytes(event),AlertPersistenceSupport.bytes(event),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),AlertPersistenceSupport.bytes(integration),Timestamp.from(now));boolean established="ESTABLISHED".equals(status);jdbc.update("INSERT INTO incident_ticket_link(tenant_id,incident_id,create_ticket_intent_key,status,ticket_id,requested_at,established_at,recovery_required_at,recovery_reason_code,retry_count,last_attempt_at,lock_version,created_at,created_by_type,created_by_id,updated_at,updated_by_type,updated_by_id) VALUES(?,?,?,?,?,?,?,?,?,8,?,0,?,'SERVICE',NULL,?,'SERVICE',NULL)",AlertPersistenceSupport.bytes(TENANT),AlertPersistenceSupport.bytes(incident),create,status,AlertPersistenceSupport.bytes(ticket),Timestamp.from(now),established?Timestamp.from(now):null,"RECOVERY_REQUIRED".equals(status)?Timestamp.from(now):null,"RECOVERY_REQUIRED".equals(status)?"BROKER_RESULT_UNKNOWN":null,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));return new Seed(incident,create);}
    /**
     * 创建指定状态的告警发件箱记录，保留契约身份及版本用于人工恢复条件更新。
     */
    private UUID seedOutbox(Seed seed,String contract){Instant now=Instant.parse("2026-08-26T02:00:00Z");UUID outbox=UUID.randomUUID(),message=UUID.randomUUID();byte[] body="{}".getBytes(StandardCharsets.UTF_8),intent=AlertEventContract.TICKET_CREATE_REQUESTED.contractRef().equals(contract)?seed.createIntent():AlertIdempotencyCodec.derivedKey(AlertEventContract.INCIDENT_RECOVERED.intentNamespace(),TENANT,seed.incident());jdbc.update("INSERT INTO outbox_event(tenant_id,outbox_id,message_id,publication_key,business_intent_key,aggregate_type,aggregate_id,contract_ref,contract_version,body_bytes,body_sha256,correlation_id,causation_id,status,attempt_count,next_attempt_at,locked_by,locked_at,last_error_code,broker_confirmed_at,lock_version,created_at,created_by_type,created_by_id,updated_at,updated_by_type,updated_by_id) VALUES(?,?,?,?,?,'INCIDENT',?,?,1,?,?,?,NULL,'RECOVERY_REQUIRED',8,?,NULL,NULL,'BROKER_RESULT_UNKNOWN',NULL,0,?,'SERVICE',NULL,?,'SERVICE',NULL)",AlertPersistenceSupport.bytes(TENANT),AlertPersistenceSupport.bytes(outbox),AlertPersistenceSupport.bytes(message),AlertIdempotencyCodec.publicationKey(TENANT,message),intent,AlertPersistenceSupport.bytes(seed.incident()),contract,body,AlertIdempotencyCodec.sha256(body),AlertPersistenceSupport.bytes(UUID.randomUUID()),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));return outbox;}
    /**
     * 插入与原消息匹配的恢复记录，设置当前状态及版本供重放 API 断言。
     */
    private UUID seedRecovery(String direction,UUID source,byte[] intent,int attempts){Instant now=Instant.parse("2026-08-26T02:00:00Z");UUID id=UUID.randomUUID();jdbc.update("INSERT INTO message_recovery(tenant_id,recovery_id,direction,source_record_id,business_intent_key,state,attempt_count,next_attempt_at,last_error_code,last_error_summary,dead_lettered_at,recovery_required_at,replay_count,last_replayed_at,last_replayed_by_type,last_replayed_by_id,last_replay_reason,last_replay_correlation_id,resolved_at,lock_version,created_at,updated_at,updated_by_type,updated_by_id) VALUES(?,?,?,?,?,'OPEN',?,NULL,'BROKER_RESULT_UNKNOWN',NULL,NULL,?,0,NULL,NULL,NULL,NULL,NULL,NULL,0,?,?,'SERVICE',NULL)",AlertPersistenceSupport.bytes(TENANT),AlertPersistenceSupport.bytes(id),direction,AlertPersistenceSupport.bytes(source),intent,attempts,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));return id;}
    /**
     * 在有界等待内轮询异步处理结果，满足断言后返回，超时则使测试失败。
     */
    private void await(String sql,byte[] id,String expected){for(int i=0;i<300;i++){if(expected.equals(jdbc.queryForObject(sql,String.class,id)))return;LockSupport.parkNanos(10_000_000L);}throw new AssertionError("Timed out waiting for "+expected);}/**
     * 为指定租户及测试主体签发认证令牌，供真实 HTTP 安全链验证请求权限。
     */
    private static String token(){return ISSUER.issueHuman(TENANT,ADMIN,Set.of(RoleCode.SERVICE_DESK_ADMIN),Set.of(PermissionCode.RECOVERY_READ_TENANT,PermissionCode.RECOVERY_REPLAY_TENANT)).token();}/**
     * 为测试生成 RSA 密钥对，签发器与验签配置共享同一对密钥。
     */
    private static KeyPair newKeyPair(){try{return KeyPairGenerator.getInstance("RSA").generateKeyPair();}catch(Exception e){throw new IllegalStateException(e);}}/**
     * 将测试公钥编码为 PEM 文本，使应用通过正常配置路径加载验签材料。
     */
    private static String publicKeyPem(){String nl=System.lineSeparator();return "-----BEGIN PUBLIC KEY-----"+nl+Base64.getEncoder().encodeToString(KEY.getPublic().getEncoded())+nl+"-----END PUBLIC KEY-----";}/**
     * 集成测试的种子资源标识集合，供同一测试中的请求、数据库断言和租户隔离验证共享。
     */
    private record Seed(UUID incident,byte[] createIntent){}
}
