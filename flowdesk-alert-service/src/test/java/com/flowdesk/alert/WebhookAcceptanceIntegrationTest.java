package com.flowdesk.alert;
import com.flowdesk.alert.mapper.AlertEventMapper;
import com.flowdesk.alert.mapper.IncidentMapper;
import com.flowdesk.alert.mapper.AlertPersistenceSupport;

import com.flowdesk.alert.service.AlertIdempotencyCodec;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证Webhook 认证和告警周期接纳的行为约束；结合可控测试数据检查正常流程、失败边界或状态收敛，避免回归破坏既有契约。
 */
@SpringBootTest(properties={
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:alert-webhook;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
        "spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:schema-test.sql",
        "flowdesk.alert.webhook.enabled=true","flowdesk.alert.webhook.timestamp-window=PT5M",
        "FLOWDESK_ALERT_DB_PASSWORD="
})
@AutoConfigureMockMvc
class WebhookAcceptanceIntegrationTest {
    @Autowired com.flowdesk.alert.service.AlertCycleReconciler reconciler;

    /**
     * 验证重启后根据已持久化等待事实补齐故障周期工作，原事件事实保持可追溯。
     */
    @Test
    void restartReconcilesPersistedWaitingFactsWithoutRewritingThem() throws Exception {
        send(body("old-fire","FIRING","backlog","old","first"),"backlog_nonce_00001",SELECTOR_A,VERIFIER_A,SECRET_A).andExpect(status().isAccepted());
        send(body("old-clear","RESOLVED","backlog","old","recovered").replace("01:00:00.000000Z","01:01:00.000000Z"),
                "backlog_nonce_00002",SELECTOR_A,VERIFIER_A,SECRET_A).andExpect(status().isAccepted());
        // Persist the exact unlinked facts produced by the earlier release.
        for(int i=0;i<2;i++) events.insertEventPreTrust(TENANT,UUID.randomUUID(),INTEGRATION_A,"waiting-"+i,"FIRING",
                "backlog","new",Instant.parse("2026-08-26T01:02:00Z").plusSeconds(i),Instant.now(),
                "REVIEW_REQUIRED",null,new byte[32],"second",UUID.randomUUID());
        reconciler.scan();reconciler.scan();
        assertThat(count("incident")).isEqualTo(2);
        assertThat(count("incident_ticket_link")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident WHERE status='ACTIVE'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_event WHERE disposition='REVIEW_REQUIRED' AND incident_id IS NULL",Integer.class)).isEqualTo(2);
        try(var ignored=com.flowdesk.common.tenant.TenantContextScope.open(com.flowdesk.common.tenant.TenantContext.ofWebhook(TENANT,INTEGRATION_A,UUID.randomUUID()))) {
            UUID id=incidents.findByCyclePreTrust(TENANT,"backlog","new").get(0).incidentId();
            var linked=new com.flowdesk.alert.model.EventFilter(null,"APPLIED",id,null,null,null,null,null);
            assertThat(events.listEvents(TENANT,linked,"receivedAt",null,null,100)).hasSize(2);
            var review=new com.flowdesk.alert.model.EventFilter(null,"REVIEW_REQUIRED",null,null,null,null,null,null);
            assertThat(events.listEvents(TENANT,review,"receivedAt",null,null,100)).isEmpty();
        }
    }

    /**
     * 验证等待中的周期已被恢复事实结束时，不再创建过时建单任务。
     */
    @Test
    void waitingCycleThatAlreadyRecoveredDoesNotCreateObsoleteWork() throws Exception {
        send(body("first-fire","FIRING","settled","first","first"),"settled_nonce_00001",SELECTOR_A,VERIFIER_A,SECRET_A).andExpect(status().isAccepted());
        send(body("next-fire","FIRING","settled","next","next").replace("01:00:00.000000Z","01:02:00.000000Z"),"settled_nonce_00002",SELECTOR_A,VERIFIER_A,SECRET_A).andExpect(status().isAccepted());
        send(body("next-clear","RESOLVED","settled","next","done").replace("01:00:00.000000Z","01:03:00.000000Z"),"settled_nonce_00003",SELECTOR_A,VERIFIER_A,SECRET_A).andExpect(status().isAccepted());
        send(body("first-clear","RESOLVED","settled","first","done").replace("01:00:00.000000Z","01:01:00.000000Z"),"settled_nonce_00004",SELECTOR_A,VERIFIER_A,SECRET_A).andExpect(status().isAccepted());
        reconciler.scan();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident WHERE status='ACTIVE'",Integer.class)).isZero();
        assertThat(count("incident")).isEqualTo(2);
        assertThat(count("incident_ticket_link")).isEqualTo(1);
    }

    /**
     * 验证迟到恢复事件使下一等待周期恰好协调一次，不重复派生建单副作用。
     */
    @Test
    void delayedRecoveryReconcilesTheNextCycleExactlyOnce() throws Exception {
        send(body("a-fire","FIRING","repeat-fault","cycle-a","first"),"sequence_nonce_00001",SELECTOR_A,VERIFIER_A,SECRET_A).andExpect(status().isAccepted());
        String next=body("b-fire","FIRING","repeat-fault","cycle-b","second").replace("01:00:00.000000Z","01:02:00.000000Z");
        send(next,"sequence_nonce_00002",SELECTOR_A,VERIFIER_A,SECRET_A).andExpect(status().isAccepted());
        send(body("a-clear","RESOLVED","repeat-fault","cycle-a","recovered").replace("01:00:00.000000Z","01:01:00.000000Z"),
                "sequence_nonce_00003",SELECTOR_A,VERIFIER_A,SECRET_A).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident WHERE status='ACTIVE'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_ticket_link",Integer.class)).isEqualTo(2);
        send(next,"sequence_nonce_00004",SELECTOR_A,VERIFIER_A,SECRET_A).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_ticket_link",Integer.class)).isEqualTo(2);
        send(body("b-clear","RESOLVED","repeat-fault","cycle-b","recovered").replace("01:00:00.000000Z","01:03:00.000000Z"),
                "sequence_nonce_00005",SELECTOR_A,VERIFIER_A,SECRET_A).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident WHERE status='ACTIVE'",Integer.class)).isZero();
    }
    private static final UUID TENANT=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID INTEGRATION_A=UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1");
    private static final UUID INTEGRATION_B=UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2");
    private static final byte[] SELECTOR_A=HexFormat.of().parseHex("00112233445566778899aabbccddeeff");
    private static final byte[] SELECTOR_B=HexFormat.of().parseHex("102132435465768798a9bacbdcedfe0f");
    private static final byte[] VERIFIER_A=AlertIdempotencyCodec.sha256("test-api-verifier-a-32-bytes-value".getBytes(StandardCharsets.UTF_8));
    private static final byte[] VERIFIER_B=AlertIdempotencyCodec.sha256("test-api-verifier-b-32-bytes-value".getBytes(StandardCharsets.UTF_8));
    private static final byte[] SECRET_A=AlertIdempotencyCodec.sha256("test-signing-secret-a".getBytes(StandardCharsets.UTF_8));
    private static final byte[] SECRET_B=AlertIdempotencyCodec.sha256("test-signing-secret-b".getBytes(StandardCharsets.UTF_8));
    private static final Path SECRET_FILE=createSecretFile();
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired AlertEventMapper events; @Autowired IncidentMapper incidents;

    /**
     * 为集成测试注册独立数据库、验签公钥及组件开关，使测试上下文使用可控基础设施。
     */
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("flowdesk.alert.webhook.secrets-file",()->SECRET_FILE.toAbsolutePath().toString());r.add("flowdesk.security.jwt.public-key-pem",()->resource("test-jwt-public-key.pem"));r.add("flowdesk.security.cursor-token.hmac-key",()->Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));}
    /**
     * 在测试前清理并重新建立所需数据库事实，避免前一个测试的消息状态或审计影响当前断言。
     */
    @BeforeEach void reset(){jdbc.execute("DELETE FROM message_recovery");jdbc.execute("DELETE FROM audit_record");jdbc.execute("DELETE FROM inbox_message");jdbc.execute("DELETE FROM outbox_event");jdbc.execute("DELETE FROM incident_ticket_link");jdbc.execute("DELETE FROM incident");jdbc.execute("DELETE FROM alert_event");jdbc.execute("DELETE FROM webhook_nonce");jdbc.execute("DELETE FROM monitoring_integration");seed(INTEGRATION_A,"MON_A",SELECTOR_A,VERIFIER_A);seed(INTEGRATION_B,"MON_B",SELECTOR_B,VERIFIER_B);}

    /**
     * 验证 Webhook 验签与事件去重联动，重复事件导致的事务回滚不会额外占用 nonce。
     */
    @Test void authenticatesAndKeepsDuplicateEventNonceRollback() throws Exception {String body=body("evt-1","FIRING","fp-a","cycle-a","first");send(body,"nonce_00000000001",SELECTOR_A,VERIFIER_A,SECRET_A).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.disposition").value("APPLIED"));
        send(body,"nonce_00000000002",SELECTOR_A,VERIFIER_A,SECRET_A).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("WEBHOOK_EVENT_DUPLICATE"));
        send(body("evt-1","FIRING","fp-a","cycle-a","changed"),"nonce_00000000003",SELECTOR_A,VERIFIER_A,SECRET_A).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("WEBHOOK_EVENT_CONFLICT"));
        assertThat(count("webhook_nonce")).isEqualTo(1);assertThat(count("alert_event")).isEqualTo(1);assertThat(count("incident")).isEqualTo(1);assertThat(count("incident_ticket_link")).isEqualTo(1);assertThat(count("outbox_event")).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_event WHERE raw_body_ref IS NOT NULL OR raw_body_ciphertext IS NOT NULL",Integer.class)).isZero();}

    /**
     * 验证 nonce 重放和错误签名均被拒绝，不留下告警事件或故障副作用。
     */
    @Test void rejectsNonceReplayAndBadSignatureWithoutEventSideEffects()throws Exception{String one=body("evt-a","FIRING","fp-n","c-n","one");send(one,"nonce_00000000011",SELECTOR_A,VERIFIER_A,SECRET_A).andExpect(status().isAccepted());String two=body("evt-b","FIRING","fp-n","c-n","two");send(two,"nonce_00000000011",SELECTOR_A,VERIFIER_A,SECRET_A).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("WEBHOOK_NONCE_REPLAYED"));
        String timestamp=Long.toString(Instant.now().getEpochSecond());mvc.perform(post("/api/v1/webhooks/monitoring/alerts").contentType("application/json;charset=UTF-8").header("X-FlowDesk-Api-Key",apiKey(SELECTOR_A,VERIFIER_A)).header("X-FlowDesk-Signature-Version","1").header("X-FlowDesk-Timestamp",timestamp).header("X-FlowDesk-Nonce","nonce_00000000012").header("X-FlowDesk-Signature","sha256="+"0".repeat(64)).content(two)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("WEBHOOK_AUTHENTICATION_FAILED"));assertThat(count("alert_event")).isEqualTo(1);assertThat(count("webhook_nonce")).isEqualTo(1);}

    /**
     * 验证不同集成可聚合同一故障指纹，同时已恢复的一代故障不会因迟到触发重新复活。
     */
    @Test void aggregatesAcrossIntegrationsAndNeverRevivesRecoveredGeneration() throws Exception {send(body("same-event","FIRING","shared-fp","cycle-1","a"),"nonce_00000000101",SELECTOR_A,VERIFIER_A,SECRET_A).andExpect(status().isAccepted());send(body("same-event","FIRING","shared-fp","cycle-1","b"),"nonce_00000000101",SELECTOR_B,VERIFIER_B,SECRET_B).andExpect(status().isAccepted());send(body("b2","RESOLVED","shared-fp","cycle-1","done"),"nonce_00000000103",SELECTOR_B,VERIFIER_B,SECRET_B).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT event_count FROM incident WHERE status='RECOVERED'",Integer.class)).isEqualTo(3);send(body("a2","FIRING","shared-fp","cycle-2","new"),"nonce_00000000104",SELECTOR_A,VERIFIER_A,SECRET_A).andExpect(status().isAccepted());send(body("old","RESOLVED","shared-fp","cycle-1","late"),"nonce_00000000105",SELECTOR_A,VERIFIER_A,SECRET_A).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.disposition").value("LATE_OLD_GENERATION"));
        send(body("unknown","RESOLVED","shared-fp","cycle-unknown","ambiguous"),"nonce_00000000106",SELECTOR_B,VERIFIER_B,SECRET_B).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.disposition").value("REVIEW_REQUIRED")).andExpect(jsonPath("$.data.incidentId").doesNotExist());
        assertThat(count("incident")).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident WHERE status='ACTIVE'",Integer.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT event_count FROM incident WHERE status='ACTIVE'",Integer.class)).isEqualTo(1);assertThat(count("incident_ticket_link")).isEqualTo(2);assertThat(count("outbox_event")).isEqualTo(3);}

    /**
     * 验证恢复先于触发到达时保留恢复事实，后续迟到触发不会派发已过时工作。
     */
    @Test void recoveryBeforeFiringDoesNotDispatchAlreadyRecoveredWork() throws Exception {
        send(body("recovery-first", "RESOLVED", "reordered-fp", "cycle-1", "recovered")
                        .replace("01:00:00.000000Z", "01:01:00.000000Z"),
                "nonce_reorder_00001", SELECTOR_A, VERIFIER_A, SECRET_A)
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.incidentStatus").value("RECOVERED"));
        send(body("late-firing", "FIRING", "reordered-fp", "cycle-1", "earlier failure"),
                "nonce_reorder_00002", SELECTOR_A, VERIFIER_A, SECRET_A)
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.disposition").value("LATE_OLD_GENERATION"));
        assertThat(count("incident")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident WHERE status='ACTIVE'", Integer.class)).isZero();
        assertThat(count("outbox_event")).isZero();
        assertThat(count("incident_ticket_link")).isZero();
        send(body("next-cycle", "FIRING", "reordered-fp", "cycle-2", "new failure"),
                "nonce_reorder_00003", SELECTOR_A, VERIFIER_A, SECRET_A).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident WHERE status='ACTIVE'", Integer.class)).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    /**
     * 验证早期版本留下的未关联恢复事件仍参与当前周期裁决，避免重复创建已恢复工单。
     */
    @Test void honorsRecoveryFactsLeftUnlinkedByEarlierReleases() throws Exception {
        UUID recoveryId = UUID.randomUUID();
        Instant received = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        events.insertEventPreTrust(TENANT, recoveryId, INTEGRATION_A, "legacy-recovery", "RESOLVED",
                "legacy-fp", "legacy-cycle", received.minusSeconds(1), received, "REVIEW_REQUIRED",
                null, new byte[32], "Earlier recovery", UUID.randomUUID());
        send(body("legacy-late-firing", "FIRING", "legacy-fp", "legacy-cycle", "earlier failure"),
                "nonce_legacy_00001", SELECTOR_A, VERIFIER_A, SECRET_A)
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.incidentStatus").value("RECOVERED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident WHERE status='ACTIVE'", Integer.class)).isZero();
        assertThat(count("outbox_event")).isZero();
        assertThat(jdbc.queryForObject("SELECT first_event_id FROM incident", byte[].class))
                .isEqualTo(AlertPersistenceSupport.bytes(recoveryId));
        assertThat(jdbc.queryForObject("SELECT disposition FROM alert_event WHERE alert_event_id=?", String.class,
                AlertPersistenceSupport.bytes(recoveryId))).isEqualTo("REVIEW_REQUIRED");
    }

    /**
     * 验证聚合及恢复时间不会早于权威开启时间，保持故障周期时间线一致。
     */
    @Test void incidentTimesNeverMoveBeforeTheAuthoritativeOpenedTime() throws Exception {
        send(body("monotonic-open","FIRING","monotonic-fp","monotonic-cycle","open"),
                "nonce_00000000121",SELECTOR_A,VERIFIER_A,SECRET_A)
                .andExpect(status().isAccepted());
        var active=incidents.findActiveForUpdatePreTrust(TENANT,"monotonic-fp").orElseThrow();
        Instant staleRequestTime=active.openedAt().minusSeconds(1);
        try(TenantContextScope ignored=TenantContextScope.open(TenantContext.ofWebhook(
                TENANT,INTEGRATION_A,UUID.randomUUID()))) {
            assertThat(incidents.aggregateFiring(TENANT,active,UUID.randomUUID(),
                    staleRequestTime,"aggregate",INTEGRATION_A)).isEqualTo(1);
            active=incidents.findActiveForUpdatePreTrust(TENANT,"monotonic-fp").orElseThrow();
            assertThat(incidents.recoverIncident(TENANT,active,UUID.randomUUID(),
                    staleRequestTime,"recovered",INTEGRATION_A)).isEqualTo(1);
        }
        Timestamp openedAt=jdbc.queryForObject("SELECT opened_at FROM incident",Timestamp.class);
        assertThat(jdbc.queryForObject("SELECT last_event_at FROM incident",Timestamp.class))
                .isEqualTo(openedAt);
        assertThat(jdbc.queryForObject("SELECT recovered_at FROM incident",Timestamp.class))
                .isEqualTo(openedAt);
        assertThat(jdbc.queryForObject("SELECT event_count FROM incident",Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM incident",String.class))
                .isEqualTo("RECOVERED");
    }

    /**
     * 验证原始报文超过大小上限时，在凭证查询和租户副作用前拒绝请求。
     */
    @Test void rejectsOversizedRawBodyBeforeCredentialLookupOrTenantEffects()throws Exception{String timestamp=Long.toString(Instant.now().getEpochSecond());String oversized="{"+" ".repeat(64*1024)+"}";mvc.perform(post("/api/v1/webhooks/monitoring/alerts").contentType("application/json;charset=UTF-8").header("X-FlowDesk-Api-Key",apiKey(SELECTOR_A,VERIFIER_A)).header("X-FlowDesk-Signature-Version","1").header("X-FlowDesk-Timestamp",timestamp).header("X-FlowDesk-Nonce","nonce_00000000201").header("X-FlowDesk-Signature","sha256="+"0".repeat(64)).content(oversized)).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.error.code").value("REQUEST_TOO_LARGE"));assertThat(count("webhook_nonce")).isZero();assertThat(count("alert_event")).isZero();}

    /**
     * 验证空报文先经过身份验签，再按业务载荷模式报告错误，保持认证处理顺序。
     */
    @Test void authenticatesBeforeClassifyingAnEmptyBodyAsInvalidSchema()throws Exception{String timestamp=Long.toString(Instant.now().getEpochSecond());String nonce="nonce_00000000211";String valid=signature(timestamp,nonce,new byte[0],SECRET_A);mvc.perform(post("/api/v1/webhooks/monitoring/alerts").contentType("application/json;charset=UTF-8").header("X-FlowDesk-Api-Key",apiKey(SELECTOR_A,VERIFIER_A)).header("X-FlowDesk-Signature-Version","1").header("X-FlowDesk-Timestamp",timestamp).header("X-FlowDesk-Nonce",nonce).header("X-FlowDesk-Signature",valid).content(new byte[0])).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("WEBHOOK_BODY_INVALID"));mvc.perform(post("/api/v1/webhooks/monitoring/alerts").contentType("application/json;charset=UTF-8").header("X-FlowDesk-Api-Key",apiKey(SELECTOR_A,VERIFIER_A)).header("X-FlowDesk-Signature-Version","1").header("X-FlowDesk-Timestamp",timestamp).header("X-FlowDesk-Nonce","nonce_00000000212").header("X-FlowDesk-Signature","sha256="+"0".repeat(64)).content(new byte[0])).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("WEBHOOK_AUTHENTICATION_FAILED"));assertThat(count("webhook_nonce")).isZero();assertThat(count("alert_event")).isZero();}

    /**
     * 按测试集成凭据签名原始报文并提交 Webhook 请求，返回用于验证状态与错误的测试响应。
     */
    private org.springframework.test.web.servlet.ResultActions send(String body,String nonce,byte[] selector,byte[] verifier,byte[] secret)throws Exception{String timestamp=Long.toString(Instant.now().getEpochSecond());String signature=signature(timestamp,nonce,body.getBytes(StandardCharsets.UTF_8),secret);return mvc.perform(post("/api/v1/webhooks/monitoring/alerts").contentType("application/json;charset=UTF-8").header("X-FlowDesk-Api-Key",apiKey(selector,verifier)).header("X-FlowDesk-Signature-Version","1").header("X-FlowDesk-Timestamp",timestamp).header("X-FlowDesk-Nonce",nonce).header("X-FlowDesk-Signature",signature).content(body));}
    /**
     * 创建本测试所需的租户、集成或消息业务事实，并返回后续请求与数据库断言使用的资源标识。
     */
    private void seed(UUID id,String code,byte[] selector,byte[] verifier){Instant now=Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);jdbc.update("INSERT INTO monitoring_integration(tenant_id,integration_id,integration_code,integration_name,status,api_key_selector_hash,api_key_verifier,api_key_verifier_scheme,signing_scheme_version,valid_from,valid_until) VALUES(?,?,?,?,'ACTIVE',?,?,'FD_API_KEY_SHA256_V1',1,?,NULL)",AlertPersistenceSupport.bytes(TENANT),AlertPersistenceSupport.bytes(id),code,code,AlertIdempotencyCodec.sha256(selector),AlertIdempotencyCodec.sha256(verifier),Timestamp.from(now));}
    /**
     * 读取当前断言关注的数据库记录数，用于验证重复消费、拒绝或失败没有额外副作用。
     */
    private int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}/**
     * 构造当前场景的告警 JSON 原始字节，供签名输入和业务解析使用同一正文。
     */
    private static String body(String id,String type,String fp,String cycle,String summary){return "{\"eventId\":\""+id+"\",\"eventType\":\""+type+"\",\"fingerprint\":\""+fp+"\",\"cycleRef\":\""+cycle+"\",\"occurredAt\":\"2026-08-26T01:00:00.000000Z\",\"summary\":\""+summary+"\"}";}
    /**
     * 将测试选择器与校验秘密编码为 Webhook API key 格式，匹配真实认证过滤器的解析规则。
     */
    private static String apiKey(byte[] selector,byte[] verifier){return "fdak1."+Base64.getUrlEncoder().withoutPadding().encodeToString(selector)+'.'+Base64.getUrlEncoder().withoutPadding().encodeToString(verifier);}/**
     * 按冻结的 Webhook 签名串规则计算 HMAC，便于真实过滤器验证时间戳、nonce 与原始正文。
     */
    private static String signature(String timestamp,String nonce,byte[] body,byte[] secret)throws Exception{byte[] prefix=("1\n"+timestamp+'\n'+nonce+"\nPOST\n/api/v1/webhooks/monitoring/alerts\n").getBytes(StandardCharsets.UTF_8);byte[] signed=new byte[prefix.length+body.length];System.arraycopy(prefix,0,signed,0,prefix.length);System.arraycopy(body,0,signed,prefix.length,body.length);Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return "sha256="+HexFormat.of().formatHex(mac.doFinal(signed));}
    /**
     * 生成测试集成签名密钥文件，供认证组件沿正常配置路径读取。
     */
    private static Path createSecretFile(){try{Path dir=Path.of("target","m7-test-runtime");Files.createDirectories(dir);Path file=dir.resolve("webhook-secrets.json");String json="{\"dummySecret\":\""+Base64.getEncoder().encodeToString(AlertIdempotencyCodec.sha256("dummy".getBytes(StandardCharsets.UTF_8)))+"\",\"integrations\":{\""+INTEGRATION_A+"\":\""+Base64.getEncoder().encodeToString(SECRET_A)+"\",\""+INTEGRATION_B+"\":\""+Base64.getEncoder().encodeToString(SECRET_B)+"\"}}";Files.writeString(file,json,StandardCharsets.UTF_8);return file;}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    /**
     * 读取消息样例或数据库初始化资源，供测试与冻结契约对照使用。
     */
    private static String resource(String name){try(var in=WebhookAcceptanceIntegrationTest.class.getClassLoader().getResourceAsStream(name)){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException(e);}}
}
