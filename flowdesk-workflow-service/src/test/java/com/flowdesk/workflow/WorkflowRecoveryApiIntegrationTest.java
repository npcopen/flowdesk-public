package com.flowdesk.workflow;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.RoleCode;
import com.flowdesk.common.security.JwtIssuer;
import com.flowdesk.workflow.service.WorkflowIdempotencyCodec;
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
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "flowdesk.workflow.start.entry.enabled=true",
        "flowdesk.workflow.start.consumer.enabled=false"
})
@AutoConfigureMockMvc
class WorkflowRecoveryApiIntegrationTest {
    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID REQUESTER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final KeyPair KEYS = keyPair();
    private static final JwtIssuer ISSUER = new JwtIssuer(KEYS.getPrivate(),
            "flowdesk-iam-service", "flowdesk-api", "flowdesk-internal", 900, 300);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.security.jwt.public-key-pem", WorkflowRecoveryApiIntegrationTest::pem);
        registry.add("flowdesk.security.cursor-token.hmac-key", WorkflowRecoveryApiIntegrationTest::cursorKey);
        registry.add("FLOWDESK_WORKFLOW_DB_PASSWORD", () -> "");
        registry.add("spring.cloud.service-registry.auto-registration.enabled", () -> "false");
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:workflow-recovery-api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:schema-test.sql");
    }

    @BeforeEach
    void clean() {
        for (String table : List.of("message_recovery", "audit_record", "inbox_message",
                "outbox_event", "approval_task", "workflow_instance", "workflow_version",
                "workflow_draft", "workflow_definition")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void workflowOutboxRecoveryIsReadableTenantScopedAndQueuesOnlyItsOriginalFact()
            throws Exception {
        UUID outbox = UUID.randomUUID();
        UUID recovery = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        byte[] body = "{\"schemaVersion\":\"1\"}".getBytes(StandardCharsets.UTF_8);
        seedOutboxRecovery(TENANT_A, outbox, recovery, message, body,
                "flowdesk.workflow.instance-established.v1");
        seedRecoveryOnly(TENANT_B, UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(get("/api/v1/workflow-message-recoveries")
                        .header("Authorization", bearer(adminToken()))
                        .param("direction", "OUTBOX").param("state", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].recoveryId").value(recovery.toString()))
                .andExpect(jsonPath("$.data[0].businessIntentDigest").isString())
                .andExpect(jsonPath("$.data[0].bodyBytes").doesNotExist());

        mockMvc.perform(get("/api/v1/workflow-message-recoveries/" + recovery)
                        .header("Authorization", bearer(requesterToken())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/workflow-message-recoveries/" + recovery)
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"lv:0\""));

        String command = "{\"reason\":\"重新发布原始反向事实\"}";
        mockMvc.perform(post("/api/v1/workflow-message-recoveries/" + recovery + "/replays")
                        .header("Authorization", bearer(adminToken()))
                        .header("Idempotency-Key", "workflow-outbox-replay-0001")
                        .header("If-Match", "\"lv:0\"")
                        .contentType("application/json").content(command))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(header().string("ETag", "\"lv:1\""))
                .andExpect(jsonPath("$.data.state").value("REPLAYING"))
                .andExpect(jsonPath("$.data.replayCount").value(1));

        assertThat(jdbc.queryForMap("""
                SELECT status,attempt_count,message_id,body_bytes,body_sha256
                FROM outbox_event WHERE tenant_id=? AND outbox_id=?
                """, uuid(TENANT_A), uuid(outbox))).satisfies(row -> {
                    assertThat(row.get("STATUS")).isEqualTo("PENDING");
                    assertThat(((Number) row.get("ATTEMPT_COUNT")).intValue()).isZero();
                    assertThat((byte[]) row.get("MESSAGE_ID")).containsExactly(uuid(message));
                    assertThat((byte[]) row.get("BODY_BYTES")).containsExactly(body);
                    assertThat((byte[]) row.get("BODY_SHA256")).containsExactly(sha256(body));
                });

        mockMvc.perform(post("/api/v1/workflow-message-recoveries/" + recovery + "/replays")
                        .header("Authorization", bearer(adminToken()))
                        .header("Idempotency-Key", "workflow-outbox-replay-0001")
                        .header("If-Match", "\"lv:0\"")
                        .contentType("application/json").content(command))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.data.replayCount").value(1));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_record
                WHERE tenant_id=? AND action_code='WORKFLOW_RECOVERY_REPLAY_REQUESTED'
                """, Integer.class, uuid(TENANT_A))).isEqualTo(1);
    }

    private void seedOutboxRecovery(UUID tenant, UUID outbox, UUID recovery, UUID message,
                                    byte[] body, String contract) {
        UUID instance = UUID.randomUUID();
        byte[] intent = bytes(2);
        jdbc.update("""
                INSERT INTO outbox_event
                  (tenant_id,outbox_id,message_id,publication_key,business_intent_key,
                   aggregate_type,aggregate_id,contract_ref,contract_version,body_bytes,
                   body_sha256,correlation_id,causation_id,status,attempt_count,next_attempt_at,
                   last_error_code,lock_version,created_by_type,updated_by_type)
                VALUES (?,?,?,?,?,'WORKFLOW_INSTANCE',?,?,1,?,?,?,?,
                        'RECOVERY_REQUIRED',8,CURRENT_TIMESTAMP,'BROKER_SEND_FAILED',0,
                        'SERVICE','SERVICE')
                """, uuid(tenant), uuid(outbox), uuid(message),
                WorkflowIdempotencyCodec.publicationKey(tenant, message), intent, uuid(instance),
                contract, body, sha256(body), uuid(UUID.randomUUID()), uuid(UUID.randomUUID()));
        jdbc.update("""
                INSERT INTO message_recovery
                  (tenant_id,recovery_id,direction,source_record_id,business_intent_key,state,
                   attempt_count,last_error_code,last_error_summary,recovery_required_at,
                   replay_count,lock_version,updated_by_type)
                VALUES (?,?, 'OUTBOX',?,?,'OPEN',8,'BROKER_SEND_FAILED','sanitized',
                        CURRENT_TIMESTAMP,0,0,'SERVICE')
                """, uuid(tenant), uuid(recovery), uuid(outbox), intent);
    }

    private void seedRecoveryOnly(UUID tenant, UUID recovery, UUID source) {
        jdbc.update("""
                INSERT INTO message_recovery
                  (tenant_id,recovery_id,direction,source_record_id,business_intent_key,state,
                   attempt_count,last_error_code,recovery_required_at,replay_count,
                   lock_version,updated_by_type)
                VALUES (?,?,'OUTBOX',?,?,'OPEN',8,'BROKER_SEND_FAILED',CURRENT_TIMESTAMP,
                        0,0,'SERVICE')
                """, uuid(tenant), uuid(recovery), uuid(source), bytes(3));
    }

    private String adminToken() {
        return ISSUER.issueHuman(TENANT_A, ADMIN, Set.of(RoleCode.SERVICE_DESK_ADMIN), Set.of(
                PermissionCode.RECOVERY_READ_TENANT,
                PermissionCode.RECOVERY_REPLAY_TENANT)).token();
    }
    private String requesterToken() {
        return ISSUER.issueHuman(TENANT_A, REQUESTER, Set.of(RoleCode.REQUESTER),
                Set.of(PermissionCode.TICKET_READ_OWN)).token();
    }
    private static String bearer(String token) { return "Bearer " + token; }
    private static byte[] uuid(UUID value) { return WorkflowIdempotencyCodec.uuidBytes(value); }
    private static byte[] bytes(int seed) {
        byte[] value = new byte[32];
        java.util.Arrays.fill(value, (byte) seed);
        return value;
    }
    private static byte[] sha256(byte[] body) {
        try { return MessageDigest.getInstance("SHA-256").digest(body); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static KeyPair keyPair() {
        try { return KeyPairGenerator.getInstance("RSA").generateKeyPair(); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static String pem() {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }
    private static String cursorKey() {
        return Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    }
}
