package com.flowdesk.workflow;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.RoleCode;
import com.flowdesk.common.security.JwtIssuer;
import com.flowdesk.workflow.service.WorkflowIdempotencyCodec;
import com.flowdesk.workflow.start.WorkflowApproverEligibilityChecker;
import com.jayway.jsonpath.JsonPath;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 流程定义 API 集成测试，覆盖草稿创建、受限校验、发布、幂等重放和并发唯一性。
 */
@SpringBootTest(properties = "flowdesk.workflow.start.entry.enabled=false")
@AutoConfigureMockMvc
class WorkflowDefinitionApiIntegrationTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ADMIN_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID REQUESTER_A = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID DEFINITION_A = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID DEFINITION_B = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID APPROVER_1 = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID APPROVER_2 = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID INELIGIBLE = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final KeyPair KEYS = newKeyPair();
    private static final JwtIssuer ISSUER = new JwtIssuer(KEYS.getPrivate(),
            "flowdesk-iam-service", "flowdesk-api", "flowdesk-internal", 900, 300);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FakeEligibility eligibility;

    @Autowired
    private com.flowdesk.workflow.mapper.WorkflowDefinitionMapper definitions;

    /**
     * 验证流程定义 MyBatis 游标保持租户条件和稳定次级排序，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void mybatisDefinitionCursorPreservesTenantAndTieBreakerOrdering() {
        UUID other = UUID.fromString("33333333-3333-3333-3333-333333333334");
        insertDefinition(TENANT_A, other, "B_FLOW", "乙流程");
        var first = definitions.findDefinitions(TENANT_A, "definitionCode", true, null, null, 1);
        assertThat(first).extracting(row -> row.definitionId()).containsExactly(DEFINITION_A, other);
        assertThat(definitions.findDefinitions(TENANT_A, "definitionCode", true, "A_FLOW", DEFINITION_A, 1))
                .extracting(row -> row.definitionId()).containsExactly(other);
        assertThat(definitions.findDefinitions(TENANT_A, "definitionCode", false, "B_FLOW", other, 1))
                .extracting(row -> row.definitionId()).containsExactly(DEFINITION_A);
        assertThat(definitions.findDefinition(TENANT_B, DEFINITION_A)).isEmpty();
        assertThat(definitions.findDefinitions(TENANT_A, "definitionName", true, null, null, 10))
                .hasSize(2);
    }

    /**
     * 注册测试专用数据库、密钥和开关配置，使应用上下文使用隔离依赖。
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.security.jwt.public-key-pem", WorkflowDefinitionApiIntegrationTest::publicKeyPem);
        registry.add("flowdesk.security.cursor-token.hmac-key", () -> Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        registry.add("FLOWDESK_WORKFLOW_DB_PASSWORD", () -> "");
        registry.add("spring.cloud.service-registry.auto-registration.enabled", () -> "false");
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:workflow-definition-api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:schema-test.sql");
    }

    /**
     * 恢复测试替身与数据库的初始状态，保证每个场景从独立前置条件开始。
     */
    @BeforeEach
    void reset() {
        for (String table : List.of("message_recovery", "audit_record", "inbox_message",
                "outbox_event", "approval_task", "workflow_instance", "workflow_version",
                "workflow_draft", "workflow_definition")) {
            jdbc.update("DELETE FROM " + table);
        }
        insertDefinition(TENANT_A, DEFINITION_A, "A_FLOW", "甲流程");
        insertDefinition(TENANT_B, DEFINITION_B, "B_FLOW", "乙流程");
        eligibility.calls = 0;
    }

    /**
     * 验证两级审批拒绝同一人担任两级办理人，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void twoLevelApprovalRejectsTheSamePerson() throws Exception {
        mockMvc.perform(post("/api/v1/workflow-definitions/" + DEFINITION_A + "/drafts")
                        .header("Authorization", "Bearer " + adminToken()).header("Idempotency-Key", "distinct-approver-create")
                        .contentType("application/json").content(draftBody(APPROVER_1, APPROVER_1, "HIGH")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("APPROVERS_MUST_DIFFER"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_draft", Integer.class)).isZero();
    }

    /**
     * 验证管理员能按受限规则创建、校验及发布草稿，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void adminCreatesValidatesAndPublishesRestrictedDraft() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/workflow-definitions/" + DEFINITION_A + "/drafts")
                        .header("Authorization", "Bearer " + adminToken())
                        .header("Idempotency-Key", "workflow-draft-create-0001")
                        .contentType("application/json")
                        .content(draftBody(APPROVER_1, APPROVER_2, "HIGH")))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"lv:0\""))
                .andExpect(jsonPath("$.data.validationState").value("UNVALIDATED"))
                .andReturn();
        UUID draftId = UUID.fromString(JsonPath.read(
                created.getResponse().getContentAsString(), "$.data.draftId"));
        assertThat(eligibility.calls).isEqualTo(2);

        mockMvc.perform(post("/api/v1/workflow-drafts/" + draftId + "/validate")
                        .header("Authorization", "Bearer " + adminToken())
                        .header("If-Match", "\"lv:0\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"lv:1\""))
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.validationDigest").isNotEmpty());

        mockMvc.perform(post("/api/v1/workflow-drafts/" + draftId + "/publish")
                        .header("Authorization", "Bearer " + adminToken())
                        .header("Idempotency-Key", "workflow-draft-publish-001")
                        .header("If-Match", "\"lv:1\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_version", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_draft", String.class))
                .isEqualTo("CONSUMED");
        assertThat(eligibility.calls).isEqualTo(4);
    }

    /**
     * 验证编辑清除旧校验且创建重试使用持久化幂等锚点，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void editClearsValidationAndCreateReplayUsesPersistentAnchor() throws Exception {
        String body = draftBody(APPROVER_1, APPROVER_2, "HIGH");
        MvcResult first = create(body, "workflow-draft-create-0002");
        UUID draftId = UUID.fromString(JsonPath.read(
                first.getResponse().getContentAsString(), "$.data.draftId"));

        mockMvc.perform(post("/api/v1/workflow-definitions/" + DEFINITION_A + "/drafts")
                        .header("Authorization", "Bearer " + adminToken())
                        .header("Idempotency-Key", "workflow-draft-create-0002")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.data.draftId").value(draftId.toString()));
        assertThat(eligibility.calls).isEqualTo(2);

        mockMvc.perform(post("/api/v1/workflow-drafts/" + draftId + "/validate")
                        .header("Authorization", "Bearer " + adminToken())
                        .header("If-Match", "\"lv:0\""))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/workflow-drafts/" + draftId)
                        .header("Authorization", "Bearer " + adminToken())
                        .header("If-Match", "\"lv:1\"")
                        .contentType("application/json")
                        .content(draftBody(APPROVER_1, APPROVER_2, "NORMAL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validationState").value("UNVALIDATED"))
                .andExpect(jsonPath("$.data.validationDigest").doesNotExist())
                .andExpect(header().string("ETag", "\"lv:2\""));
    }

    /**
     * 验证未知规则结构及不合格审批人不会产生写入，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void unknownStructureAndIneligibleApproverWriteNothing() throws Exception {
        mockMvc.perform(post("/api/v1/workflow-definitions/" + DEFINITION_A + "/drafts")
                        .header("Authorization", "Bearer " + adminToken())
                        .header("Idempotency-Key", "workflow-draft-unknown-001")
                        .contentType("application/json")
                        .content("""
                                {"condition":{"fieldCode":"SERVICE_RISK_LEVEL","operator":"EQUALS",
                                "valueType":"STRING","value":"HIGH","script":"return true"},
                                "level1ApproverUserId":"%s","level2ApproverUserId":"%s"}
                                """.formatted(APPROVER_1, APPROVER_2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNKNOWN_FIELD"));
        assertThat(eligibility.calls).isZero();

        mockMvc.perform(post("/api/v1/workflow-definitions/" + DEFINITION_A + "/drafts")
                        .header("Authorization", "Bearer " + adminToken())
                        .header("Idempotency-Key", "workflow-draft-ineligible-1")
                        .contentType("application/json")
                        .content(draftBody(APPROVER_1, INELIGIBLE, "HIGH")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("WORKFLOW_APPROVER_NOT_ELIGIBLE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_draft", Integer.class))
                .isZero();
    }

    /**
     * 验证权限和租户范围违规默认拒绝，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void permissionsAndTenantScopeAreFailClosed() throws Exception {
        mockMvc.perform(get("/api/v1/workflow-definitions")
                        .header("Authorization", "Bearer " + requesterToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/workflow-definitions/" + DEFINITION_B)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    /**
     * 验证并发创建及并发发布均只产生一个成功结果，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void concurrentDraftCreationAndPublicationEachHaveOneWinner() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier createBarrier = new CyclicBarrier(2);
            Future<MvcResult> createOne = executor.submit(() -> {
                createBarrier.await();
                return concurrentCreate("workflow-draft-concurrent-0001");
            });
            Future<MvcResult> createTwo = executor.submit(() -> {
                createBarrier.await();
                return concurrentCreate("workflow-draft-concurrent-0002");
            });
            List<MvcResult> createResults = List.of(createOne.get(), createTwo.get());
            assertThat(createResults.stream()
                    .map(result -> result.getResponse().getStatus()).sorted().toList())
                    .containsExactly(201, 409);
            MvcResult created = createResults.stream()
                    .filter(result -> result.getResponse().getStatus() == 201)
                    .findFirst().orElseThrow();
            UUID draftId = UUID.fromString(JsonPath.read(
                    created.getResponse().getContentAsString(), "$.data.draftId"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_draft", Integer.class))
                    .isEqualTo(1);

            MvcResult validated = mockMvc.perform(post("/api/v1/workflow-drafts/"
                            + draftId + "/validate")
                            .header("Authorization", "Bearer " + adminToken())
                            .header("If-Match", "\"lv:0\""))
                    .andExpect(status().isOk())
                    .andReturn();
            String publishEtag = validated.getResponse().getHeader("ETag");

            CyclicBarrier publishBarrier = new CyclicBarrier(2);
            Future<MvcResult> publishOne = executor.submit(() -> {
                publishBarrier.await();
                return concurrentPublish(draftId, publishEtag,
                        "workflow-publish-concurrent-0001");
            });
            Future<MvcResult> publishTwo = executor.submit(() -> {
                publishBarrier.await();
                return concurrentPublish(draftId, publishEtag,
                        "workflow-publish-concurrent-0002");
            });
            List<MvcResult> publishResults = List.of(publishOne.get(), publishTwo.get());
            assertThat(publishResults.stream()
                    .map(result -> result.getResponse().getStatus()).sorted().toList())
                    .containsExactly(201, 409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_version", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM workflow_draft", String.class))
                    .isEqualTo("CONSUMED");
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM audit_record
                    WHERE action_code='WORKFLOW_DRAFT_PUBLISH' AND outcome='SUCCEEDED'
                    """, Integer.class)).isEqualTo(1);
        }
        finally {
            executor.shutdownNow();
        }
    }

    /**
     * 发起并发草稿创建请求，保留响应供单一胜者及冲突结果断言。
     */
    private MvcResult concurrentCreate(String key) throws Exception {
        return mockMvc.perform(post("/api/v1/workflow-definitions/" + DEFINITION_A + "/drafts")
                        .header("Authorization", "Bearer " + adminToken())
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(draftBody(APPROVER_1, APPROVER_2, "HIGH")))
                .andReturn();
    }

    /**
     * 发起带版本及幂等键的并发发布请求，供唯一版本断言。
     */
    private MvcResult concurrentPublish(UUID draftId, String etag, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/workflow-drafts/" + draftId + "/publish")
                        .header("Authorization", "Bearer " + adminToken())
                        .header("If-Match", etag)
                        .header("Idempotency-Key", key))
                .andReturn();
    }

    /**
     * 调用创建草稿 API 并返回原始结果，供并发与幂等测试检查状态和响应头。
     */
    private MvcResult create(String body, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/workflow-definitions/" + DEFINITION_A + "/drafts")
                        .header("Authorization", "Bearer " + adminToken())
                        .header("Idempotency-Key", key)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn();
    }

    /**
     * 构造受限草稿 JSON，供不同审批人及条件值的 API 场景复用。
     */
    private static String draftBody(UUID level1, UUID level2, String value) {
        return """
                {"condition":{"fieldCode":"SERVICE_RISK_LEVEL","operator":"EQUALS",
                "valueType":"STRING","value":"%s"},"level1ApproverUserId":"%s",
                "level2ApproverUserId":"%s"}
                """.formatted(value, level1, level2);
    }

    /**
     * 建立当前测试场景的持久化前置事实，保留指定租户、身份和状态供后续业务路径验证。
     */
    private void insertDefinition(UUID tenant, UUID id, String code, String name) {
        jdbc.update("""
                INSERT INTO workflow_definition
                  (tenant_id, definition_id, definition_code, definition_name, status,
                   lock_version, created_by_type, updated_by_type)
                VALUES (?, ?, ?, ?, 'ACTIVE', 0, 'SERVICE', 'SERVICE')
                """, WorkflowIdempotencyCodec.uuidBytes(tenant),
                WorkflowIdempotencyCodec.uuidBytes(id), code, name);
    }

    /**
     * 签发当前场景需要的测试身份令牌，固定租户、角色与最小权限供真实认证路径校验。
     */
    private String adminToken() {
        return ISSUER.issueHuman(TENANT_A, ADMIN_A, Set.of(RoleCode.SERVICE_DESK_ADMIN),
                Set.of(PermissionCode.WORKFLOW_READ_TENANT,
                        PermissionCode.WORKFLOW_DRAFT_MANAGE,
                        PermissionCode.WORKFLOW_PUBLISH)).token();
    }

    /**
     * 签发当前场景需要的测试身份令牌，固定租户、角色与最小权限供真实认证路径校验。
     */
    private String requesterToken() {
        return ISSUER.issueHuman(TENANT_A, REQUESTER_A, Set.of(RoleCode.REQUESTER),
                Set.of(PermissionCode.TICKET_READ_OWN)).token();
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
        String base64 = Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
    }

    /**
     * 可控制资格结果及依赖故障的测试替身，记录调用用于验证事务外预检和竞争处理。
     */
    @TestConfiguration
    static class EligibilityConfiguration {
        /**
         * 装配当前测试使用的替身组件，替换外部依赖并保留可控结果与调用观察。
         */
        @Bean
        @Primary
        FakeEligibility fakeWorkflowEligibility() {
            return new FakeEligibility();
        }
    }

    /**
     * 可控制资格结果及依赖故障的测试替身，记录调用用于验证事务外预检和竞争处理。
     */
    static final class FakeEligibility implements WorkflowApproverEligibilityChecker {
        int calls;

        /**
         * 返回可配置的测试审批资格结果并记录调用次数，供预检、依赖失败及并发路径断言。
         */
        @Override
        public boolean isEligibleApprover(UUID tenantId, UUID approverUserId) {
            calls++;
            return !INELIGIBLE.equals(approverUserId);
        }
    }
}
