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

/**
 * 工作流恢复 API 集成测试，覆盖恢复路由可见性、权限、版本及原 Outbox 重排队。
 */
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

    /**
     * 注册测试专用数据库、密钥和开关配置，使应用上下文使用隔离依赖。
     */
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

    /**
     * 清空本测试涉及的持久化表，隔离用例之间的 Inbox、恢复、审计及业务状态。
     */
    @BeforeEach
    void clean() {
        for (String table : List.of("message_recovery", "audit_record", "inbox_message",
                "outbox_event", "approval_task", "workflow_instance", "workflow_version",
                "workflow_draft", "workflow_definition")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    /**
     * 验证Outbox 恢复按租户可读且只重新排队原始事实，并检查相关业务事实或错误响应符合约定。
     */
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

    /**
     * 建立当前测试场景的持久化前置事实，保留指定租户、身份和状态供后续业务路径验证。
     */
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

    /**
     * 建立当前测试场景的持久化前置事实，保留指定租户、身份和状态供后续业务路径验证。
     */
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

    /**
     * 签发当前场景需要的测试身份令牌，固定租户、角色与最小权限供真实认证路径校验。
     */
    private String adminToken() {
        return ISSUER.issueHuman(TENANT_A, ADMIN, Set.of(RoleCode.SERVICE_DESK_ADMIN), Set.of(
                PermissionCode.RECOVERY_READ_TENANT,
                PermissionCode.RECOVERY_REPLAY_TENANT)).token();
    }
    /**
     * 签发当前场景需要的测试身份令牌，固定租户、角色与最小权限供真实认证路径校验。
     */
    private String requesterToken() {
        return ISSUER.issueHuman(TENANT_A, REQUESTER, Set.of(RoleCode.REQUESTER),
                Set.of(PermissionCode.TICKET_READ_OWN)).token();
    }
    /**
     * 为测试令牌添加 Bearer 认证前缀，供真实 HTTP 安全过滤链读取。
     */
    private static String bearer(String token) { return "Bearer " + token; }
    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    private static byte[] uuid(UUID value) { return WorkflowIdempotencyCodec.uuidBytes(value); }
    /**
     * 构造按固定字节填充的测试数组，用于生成可重复的摘要、意图键和非法内容变体。
     */
    private static byte[] bytes(int seed) {
        byte[] value = new byte[32];
        java.util.Arrays.fill(value, (byte) seed);
        return value;
    }
    /**
     * 计算输入字节的 SHA-256 摘要，用于内容一致性核对及稳定业务键派生。
     */
    private static byte[] sha256(byte[] body) {
        try { return MessageDigest.getInstance("SHA-256").digest(body); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    /**
     * 生成测试专用 RSA 密钥对，供真实 JWT 编码和认证断言复用。
     */
    private static KeyPair keyPair() {
        try { return KeyPairGenerator.getInstance("RSA").generateKeyPair(); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    /**
     * 将测试 RSA 公钥包装为 PEM 文本，供动态配置绑定。
     */
    private static String pem() {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }
    /**
     * 生成固定测试游标签名密钥，使翻页编码与验签可重复。
     */
    private static String cursorKey() {
        return Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    }
}
