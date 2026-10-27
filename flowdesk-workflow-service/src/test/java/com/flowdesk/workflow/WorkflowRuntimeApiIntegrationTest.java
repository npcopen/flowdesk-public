package com.flowdesk.workflow;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.RoleCode;
import com.flowdesk.common.security.JwtIssuer;
import com.flowdesk.workflow.service.WorkflowIdempotencyCodec;
import com.flowdesk.workflow.start.WorkflowApproverEligibilityChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审批运行时 API 集成测试，覆盖两级推进、审批人交接、可见范围、并发决定与终态保护。
 */
@SpringBootTest(properties = "flowdesk.workflow.start.entry.enabled=false")
@AutoConfigureMockMvc
class WorkflowRuntimeApiIntegrationTest {

    @Autowired
    private com.flowdesk.workflow.mapper.WorkflowRuntimeMapper runtimeMapper;

    @Autowired
    private com.flowdesk.workflow.mapper.WorkflowDefinitionMapper definitionMapper;

    /**
     * 验证运行时和版本游标保持可见范围与二进制 UUID 映射，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void mybatisRuntimeAndVersionCursorsKeepScopeAndBinaryUuidMapping() {
        Instant startedAt = Instant.parse("2026-08-24T10:00:00Z");
        assertThat(runtimeMapper.findLevel1Approver(TENANT, INSTANCE)).contains(APPROVER_1);
        assertThat(runtimeMapper.findLevel2Approver(TENANT, INSTANCE)).contains(APPROVER_2);
        assertThat(runtimeMapper.findInstances(TENANT, "RUNNING", TICKET, VERSION, APPROVER_1,
                true, null, null, 10)).extracting(row -> row.workflowInstanceId()).containsExactly(INSTANCE);
        assertThat(runtimeMapper.findInstances(TENANT, "RUNNING", TICKET, VERSION, OTHER_APPROVER,
                true, null, null, 10)).isEmpty();
        assertThat(runtimeMapper.findInstances(TENANT, null, null, null, null,
                true, startedAt, INSTANCE, 10)).isEmpty();
        assertThat(runtimeMapper.findTasks(TENANT, "PENDING", 1, INSTANCE, APPROVER_1,
                "-decidedAt", null, null, 10)).extracting(row -> row.approvalTaskId()).containsExactly(TASK_1);
        assertThat(runtimeMapper.findTasks(TENANT, "PENDING", 1, INSTANCE, APPROVER_1,
                "-decidedAt", startedAt.toString(), TASK_1, 10)).isEmpty();
        assertThat(definitionMapper.findVersions(TENANT, DEFINITION, 1, "versionNo", null, null, 10))
                .extracting(row -> row.workflowVersionId()).containsExactly(VERSION);
        assertThat(definitionMapper.findVersions(TENANT, DEFINITION, 1, "versionNo", "1", VERSION, 10))
                .isEmpty();
        assertThat(definitionMapper.findVersions(TENANT, DEFINITION, null, "-publishedAt",
                startedAt.toString(), VERSION, 10)).isEmpty();
        jdbc.update("UPDATE approval_task SET lock_version=7 WHERE tenant_id=? AND approval_task_id=?",
                bytes(TENANT), bytes(TASK_1));
        assertThat(runtimeMapper.findTask(TENANT, TASK_1, false).orElseThrow().lockVersion()).isEqualTo(7);
    }
    /**
     * 验证审批完成事件包含给申请人的说明，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void finalizedEventIncludesTheExplanationGivenToTheApplicant() throws Exception {
        String reason="缺少访问用途，请补充后重新申请。";
        mockMvc.perform(post("/api/v1/approval-tasks/"+TASK_1+"/reject").header("Authorization","Bearer "+approverToken(APPROVER_1))
                .header("If-Match","\"lv:0\"").header("Idempotency-Key","reject-with-visible-reason")
                .contentType("application/json").content("{\"reason\":\""+reason+"\"}"))
                .andExpect(status().isOk());
        byte[] body=jdbc.queryForObject("SELECT body_bytes FROM outbox_event WHERE contract_ref='flowdesk.workflow.approval-finalized.v1'",byte[].class);
        assertThat(new tools.jackson.databind.ObjectMapper().readTree(body).path("decisionReason").asText()).isEqualTo(reason);
    }

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID APPROVER_1 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID APPROVER_2 = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID OTHER_APPROVER = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID DRAFT = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID VERSION = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID INSTANCE = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID TICKET = UUID.fromString("10000000-0000-0000-0000-000000000005");
    private static final UUID TASK_1 = UUID.fromString("10000000-0000-0000-0000-000000000006");
    private static final UUID START_MESSAGE = UUID.fromString("10000000-0000-0000-0000-000000000007");
    private static final UUID INBOX = UUID.fromString("10000000-0000-0000-0000-000000000008");
    private static final KeyPair KEYS = newKeyPair();
    private static final JwtIssuer ISSUER = new JwtIssuer(KEYS.getPrivate(),
            "flowdesk-iam-service", "flowdesk-api", "flowdesk-internal", 900, 300);

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RuntimeEligibility eligibility;

    /**
     * 注册测试专用数据库、密钥和开关配置，使应用上下文使用隔离依赖。
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.security.jwt.public-key-pem", WorkflowRuntimeApiIntegrationTest::publicKeyPem);
        registry.add("flowdesk.security.cursor-token.hmac-key", () -> Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        registry.add("FLOWDESK_WORKFLOW_DB_PASSWORD", () -> "");
        registry.add("spring.cloud.service-registry.auto-registration.enabled", () -> "false");
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:workflow-runtime-api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:schema-test.sql");
    }

    /**
     * 建立当前测试场景的持久化前置事实，保留指定租户、身份和状态供后续业务路径验证。
     */
    @BeforeEach
    void seed() {
        for (String table : List.of("workflow_approver_override", "workflow_approver_change", "message_recovery", "audit_record", "inbox_message",
                "outbox_event", "approval_task", "workflow_instance", "workflow_version",
                "workflow_draft", "workflow_definition")) {
            jdbc.update("DELETE FROM " + table);
        }
        eligibility.calls = 0;
        eligibility.inactive = null;
        Instant now = Instant.parse("2026-08-24T10:00:00Z");
        byte[] key = WorkflowIdempotencyCodec.derivedKey("TEST-START", TENANT, TICKET);
        byte[] snapshot = "{\"schemaVersion\":\"1\",\"serviceRiskLevel\":\"HIGH\"}"
                .getBytes(StandardCharsets.UTF_8);
        jdbc.update("""
                INSERT INTO workflow_definition
                  (tenant_id, definition_id, definition_code, definition_name, status,
                   created_by_type, updated_by_type)
                VALUES (?, ?, 'APPROVAL', '审批', 'ACTIVE', 'SERVICE', 'SERVICE')
                """, bytes(TENANT), bytes(DEFINITION));
        jdbc.update("""
                INSERT INTO workflow_draft
                  (tenant_id, draft_id, definition_id, status, condition_field_code,
                   condition_operator, condition_value_type, condition_value_text,
                   approval_level_count, level1_approver_user_id, level2_approver_user_id,
                   validation_state, validation_digest, validated_at, consumed_at,
                   created_at, created_by_type, updated_at, updated_by_type)
                VALUES (?, ?, ?, 'CONSUMED', 'SERVICE_RISK_LEVEL', 'EQUALS', 'STRING',
                        'HIGH', 2, ?, ?, 'VALID', ?, ?, ?, ?, 'SERVICE', ?, 'SERVICE')
                """, bytes(TENANT), bytes(DRAFT), bytes(DEFINITION), bytes(APPROVER_1),
                bytes(APPROVER_2), new byte[32], Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO workflow_version
                  (tenant_id, workflow_version_id, definition_id, version_no,
                   source_draft_id, status, condition_field_code, condition_operator,
                   condition_value_type, condition_value_text, approval_level_count,
                   level1_approver_user_id, level2_approver_user_id, snapshot_sha256,
                   published_at, published_by_type)
                VALUES (?, ?, ?, 1, ?, 'PUBLISHED', 'SERVICE_RISK_LEVEL', 'EQUALS',
                        'STRING', 'HIGH', 2, ?, ?, ?, ?, 'SERVICE')
                """, bytes(TENANT), bytes(VERSION), bytes(DEFINITION), bytes(DRAFT),
                bytes(APPROVER_1), bytes(APPROVER_2), new byte[32], Timestamp.from(now));
        jdbc.update("""
                INSERT INTO workflow_instance
                  (tenant_id, workflow_instance_id, ticket_id, start_intent_key,
                   definition_id, workflow_version_id, workflow_version_no,
                   input_snapshot_json, input_snapshot_sha256, condition_result,
                   status, current_level, started_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, ? FORMAT JSON, ?, TRUE, 'RUNNING', 1, ?)
                """, bytes(TENANT), bytes(INSTANCE), bytes(TICKET), key, bytes(DEFINITION),
                bytes(VERSION), new String(snapshot, StandardCharsets.UTF_8),
                WorkflowIdempotencyCodec.sha256(snapshot), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO inbox_message
                  (tenant_id, inbox_id, producer_service, message_id, business_intent_key,
                   contract_ref, contract_version, body_bytes, body_sha256, correlation_id,
                   occurred_at, status, result_type, result_id, attempt_count,
                   first_received_at, last_attempt_at, processed_at)
                VALUES (?, ?, 'flowdesk-ticket-service', ?, ?, 'flowdesk.workflow.start.v1',
                        1, ?, ?, ?, ?, 'PROCESSED', 'WORKFLOW_INSTANCE', ?, 1, ?, ?, ?)
                """, bytes(TENANT), bytes(INBOX), bytes(START_MESSAGE), key, new byte[]{1},
                WorkflowIdempotencyCodec.sha256(new byte[]{1}), bytes(UUID.randomUUID()),
                Timestamp.from(now), bytes(INSTANCE), Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now));
        jdbc.update("""
                INSERT INTO approval_task
                  (tenant_id, approval_task_id, workflow_instance_id, level_no,
                   approver_user_id, status, created_at)
                VALUES (?, ?, ?, 1, ?, 'PENDING', ?)
                """, bytes(TENANT), bytes(TASK_1), bytes(INSTANCE), bytes(APPROVER_1),
                Timestamp.from(now));
    }

    /**
     * 验证管理员可交接待办并保留历史及幂等重放保护，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void administratorCanHandOffPendingApprovalWithHistoryAndReplayProtection() throws Exception {
        String path = "/api/v1/workflow-instances/" + INSTANCE + "/approver-changes";
        String body = "{\"levelNo\":1,\"approverUserId\":\"" + OTHER_APPROVER + "\",\"reason\":\"主管离职交接\"}";
        mockMvc.perform(post(path).header("Authorization", "Bearer " + adminToken())
                        .header("If-Match", "\"lv:0\"").header("Idempotency-Key", "handoff-current-01")
                        .contentType("application/json").content(body)).andExpect(status().isForbidden());
        for (int i = 0; i < 2; i++) {
            var response = mockMvc.perform(post(path).header("Authorization", "Bearer " + handoffAdminToken())
                            .header("If-Match", "\"lv:0\"").header("Idempotency-Key", "handoff-current-01")
                            .contentType("application/json").content(body)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.lockVersion").value(1));
            if (i == 1) response.andExpect(header().string("Idempotency-Replayed", "true"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_approver_change", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/approval-tasks/" + TASK_1).header("Authorization", "Bearer " + approverToken(APPROVER_1)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/approval-tasks/" + TASK_1 + "/approve")
                        .header("Authorization", "Bearer " + approverToken(OTHER_APPROVER)).header("If-Match", "\"lv:1\"")
                        .header("Idempotency-Key", "handoff-new-approve").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(path).header("Authorization", "Bearer " + handoffAdminToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].reason").value("主管离职交接"));
        mockMvc.perform(post(path).header("Authorization", "Bearer " + handoffAdminToken())
                        .header("If-Match", "\"lv:2\"").header("Idempotency-Key", "handoff-completed-level")
                        .contentType("application/json").content(body)).andExpect(status().isConflict());
    }

    /**
     * 验证未来级别失效审批人可替换且不改变已发布版本，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void inactiveFutureApproverCanBeReplacedWithoutChangingThePublishedVersion() throws Exception {
        eligibility.inactive = APPROVER_2;
        mockMvc.perform(post("/api/v1/workflow-instances/" + INSTANCE + "/approver-changes")
                        .header("Authorization", "Bearer " + handoffAdminToken()).header("If-Match", "\"lv:0\"")
                        .header("Idempotency-Key", "handoff-future-01").contentType("application/json")
                        .content("{\"levelNo\":2,\"approverUserId\":\"" + OTHER_APPROVER + "\",\"reason\":\"第二级主管离职\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isZero();
        mockMvc.perform(post("/api/v1/approval-tasks/" + TASK_1 + "/approve")
                        .header("Authorization", "Bearer " + approverToken(APPROVER_1)).header("If-Match", "\"lv:0\"")
                        .header("Idempotency-Key", "handoff-future-approve").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        assertThat(uuid(jdbc.queryForObject("SELECT approver_user_id FROM approval_task WHERE level_no=2", byte[].class))).isEqualTo(OTHER_APPROVER);
        assertThat(uuid(jdbc.queryForObject("SELECT level2_approver_user_id FROM workflow_version", byte[].class))).isEqualTo(APPROVER_2);
    }

    /**
     * 验证交接拒绝另一级办理人及停用用户，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void handoffRejectsTheOtherLevelApproverAndInactivePeople() throws Exception {
        String path = "/api/v1/workflow-instances/" + INSTANCE + "/approver-changes";
        for (UUID next : List.of(APPROVER_2, OTHER_APPROVER)) {
            if (next.equals(OTHER_APPROVER)) eligibility.inactive = OTHER_APPROVER;
            mockMvc.perform(post(path).header("Authorization", "Bearer " + handoffAdminToken())
                            .header("If-Match", "\"lv:0\"").header("Idempotency-Key", "handoff-invalid-" + next)
                            .contentType("application/json").content("{\"levelNo\":1,\"approverUserId\":\"" + next + "\",\"reason\":\"交接\"}"))
                    .andExpect(status().isUnprocessableEntity());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_approver_change", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT lock_version FROM workflow_instance", Integer.class)).isZero();
    }

    /**
     * 验证首级通过仅创建第二级任务且第二级通过只终结一次，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void level1ApproveCreatesOnlyLevel2TaskThenLevel2ApproveFinalizesOnce() throws Exception {
        mockMvc.perform(post("/api/v1/approval-tasks/" + TASK_1 + "/approve")
                        .header("Authorization", "Bearer " + approverToken(APPROVER_1))
                        .header("If-Match", "\"lv:0\"")
                        .header("Idempotency-Key", "approve-level-one-0001")
                        .contentType("application/json").content("{\"reason\":\"同意\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(header().string("ETag", "\"lv:1\""));
        assertThat(eligibility.calls).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_task", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT current_level FROM workflow_instance", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE contract_ref=?",
                Integer.class, "flowdesk.workflow.notification-requested.v1")).isEqualTo(1);

        UUID level2 = uuid(jdbc.queryForObject(
                "SELECT approval_task_id FROM approval_task WHERE level_no = 2", byte[].class));
        mockMvc.perform(post("/api/v1/approval-tasks/" + level2 + "/approve")
                        .header("Authorization", "Bearer " + approverToken(APPROVER_2))
                        .header("If-Match", "\"lv:0\"")
                        .header("Idempotency-Key", "approve-level-two-0001")
                        .contentType("application/json").content("{\"reason\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_instance", String.class))
                .isEqualTo("COMPLETED_PASSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE contract_ref=?",
                Integer.class, "flowdesk.workflow.approval-finalized.v1")).isEqualTo(1);

        mockMvc.perform(post("/api/v1/approval-tasks/" + level2 + "/approve")
                        .header("Authorization", "Bearer " + approverToken(APPROVER_2))
                        .header("If-Match", "\"lv:0\"")
                        .header("Idempotency-Key", "approve-level-two-0001")
                        .contentType("application/json").content("{\"reason\":null}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class))
                .isEqualTo(2);
    }

    /**
     * 验证首级拒绝即终结且相反决定发生冲突，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void level1RejectIsTerminalAndOppositeDecisionConflicts() throws Exception {
        mockMvc.perform(post("/api/v1/approval-tasks/" + TASK_1 + "/reject")
                        .header("Authorization", "Bearer " + approverToken(APPROVER_1))
                        .header("If-Match", "\"lv:0\"")
                        .header("Idempotency-Key", "reject-level-one-0001")
                        .contentType("application/json").content("{\"reason\":\"风险过高\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_instance", String.class))
                .isEqualTo("COMPLETED_REJECTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_task", Integer.class))
                .isEqualTo(1);
        assertThat(new String(jdbc.queryForObject(
                "SELECT body_bytes FROM outbox_event", byte[].class), StandardCharsets.UTF_8))
                .contains("\"outcome\":\"REJECTED\"");

        mockMvc.perform(post("/api/v1/approval-tasks/" + TASK_1 + "/approve")
                        .header("Authorization", "Bearer " + approverToken(APPROVER_1))
                        .header("If-Match", "\"lv:1\"")
                        .header("Idempotency-Key", "opposite-decision-key-1")
                        .contentType("application/json").content("{\"reason\":\"风险过高\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("APPROVAL_DECISION_CONFLICT"));
    }

    /**
     * 验证固定审批人范围不可越权探测且管理员只有读取权，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void fixedApproverScopeIsOpaqueAndAdminIsReadOnly() throws Exception {
        mockMvc.perform(get("/api/v1/approval-tasks/" + TASK_1)
                        .header("Authorization", "Bearer " + approverToken(OTHER_APPROVER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("APPROVAL_TASK_NOT_ASSIGNED"));

        mockMvc.perform(get("/api/v1/approval-tasks")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(post("/api/v1/approval-tasks/" + TASK_1 + "/reject")
                        .header("Authorization", "Bearer " + adminToken())
                        .header("If-Match", "\"lv:0\"")
                        .header("Idempotency-Key", "admin-cannot-decide-001")
                        .contentType("application/json").content("{\"reason\":\"不允许\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * 验证并发相反决定只有一个稳定胜者且无部分提交副作用，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void concurrentOppositeDecisionsHaveOneStableWinnerAndNoPartialEffects() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<MvcResult> approve = executor.submit(() -> {
                barrier.await();
                return concurrentDecision("approve", "并发同意", "concurrent-approve-0001");
            });
            Future<MvcResult> reject = executor.submit(() -> {
                barrier.await();
                return concurrentDecision("reject", "并发拒绝", "concurrent-reject-0001");
            });
            List<MvcResult> results = List.of(approve.get(), reject.get());
            assertThat(results.stream()
                    .map(result -> result.getResponse().getStatus()).sorted().toList())
                    .containsExactly(200, 409);

            String taskStatus = jdbc.queryForObject(
                    "SELECT status FROM approval_task WHERE approval_task_id=?",
                    String.class, bytes(TASK_1));
            String instanceStatus = jdbc.queryForObject(
                    "SELECT status FROM workflow_instance WHERE workflow_instance_id=?",
                    String.class, bytes(INSTANCE));
            if ("APPROVED".equals(taskStatus)) {
                assertThat(instanceStatus).isEqualTo("RUNNING");
                assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM approval_task", Integer.class)).isEqualTo(2);
                assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM outbox_event", Integer.class)).isEqualTo(1);
            }
            else {
                assertThat(taskStatus).isEqualTo("REJECTED");
                assertThat(instanceStatus).isEqualTo("COMPLETED_REJECTED");
                assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM approval_task", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM outbox_event", Integer.class)).isEqualTo(1);
            }
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM audit_record
                    WHERE target_type='APPROVAL_TASK' AND target_id=?
                      AND action_code IN ('APPROVAL_TASK_APPROVED','APPROVAL_TASK_REJECTED')
                    """, Integer.class, bytes(TASK_1))).isEqualTo(1);
        }
        finally {
            executor.shutdownNow();
        }
    }

    /**
     * 提交并发审批决定，供相反方向竞争及事务完整性验证。
     */
    private MvcResult concurrentDecision(String direction, String reason, String key)
            throws Exception {
        return mockMvc.perform(post("/api/v1/approval-tasks/" + TASK_1 + "/" + direction)
                        .header("Authorization", "Bearer " + approverToken(APPROVER_1))
                        .header("If-Match", "\"lv:0\"")
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andReturn();
    }

    /**
     * 签发当前场景需要的测试身份令牌，固定租户、角色与最小权限供真实认证路径校验。
     */
    private String approverToken(UUID actor) {
        return ISSUER.issueHuman(TENANT, actor, Set.of(RoleCode.APPROVER),
                Set.of(PermissionCode.APPROVAL_READ_ASSIGNED,
                        PermissionCode.APPROVAL_DECIDE_ASSIGNED)).token();
    }

    /**
     * 签发当前场景需要的测试身份令牌，固定租户、角色与最小权限供真实认证路径校验。
     */
    private String adminToken() {
        return ISSUER.issueHuman(TENANT, ADMIN, Set.of(RoleCode.SERVICE_DESK_ADMIN),
                Set.of(PermissionCode.WORKFLOW_RUNTIME_READ_TENANT)).token();
    }

    /**
     * 签发当前场景需要的测试身份令牌，固定租户、角色与最小权限供真实认证路径校验。
     */
    private String handoffAdminToken() {
        return ISSUER.issueHuman(TENANT, ADMIN, Set.of(RoleCode.SERVICE_DESK_ADMIN),
                Set.of(PermissionCode.WORKFLOW_RUNTIME_READ_TENANT, PermissionCode.APPROVAL_REASSIGN_TENANT)).token();
    }

    /**
     * 将 UUID 编码为数据库使用的二进制值，空标识保持为空。
     */
    private static byte[] bytes(UUID value) {
        return WorkflowIdempotencyCodec.uuidBytes(value);
    }

    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    private static UUID uuid(byte[] value) {
        return WorkflowIdempotencyCodec.uuid(value);
    }

    /**
     * 生成仅供测试签发与验签的 RSA 密钥对，使身份流程无需外部密钥文件。
     */
    private static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("RSA").generateKeyPair();
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 将测试公钥编码为 PEM，供应用配置中的实际验签器加载。
     */
    private static String publicKeyPem() {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    /**
     * 可控制资格结果及依赖故障的测试替身，记录调用用于验证事务外预检和竞争处理。
     */
    @TestConfiguration
    static class RuntimeEligibilityConfiguration {
        /**
         * 装配当前测试使用的替身组件，替换外部依赖并保留可控结果与调用观察。
         */
        @Bean @Primary
        RuntimeEligibility runtimeEligibility() {
            return new RuntimeEligibility();
        }
    }

    /**
     * 可控制资格结果及依赖故障的测试替身，记录调用用于验证事务外预检和竞争处理。
     */
    static final class RuntimeEligibility implements WorkflowApproverEligibilityChecker {
        int calls;
        UUID inactive;
        /**
         * 返回可配置的测试审批资格结果并记录调用次数，供预检、依赖失败及并发路径断言。
         */
        @Override public boolean isEligibleApprover(UUID tenantId, UUID approverUserId) {
            calls++;
            return !approverUserId.equals(inactive);
        }
    }
}
