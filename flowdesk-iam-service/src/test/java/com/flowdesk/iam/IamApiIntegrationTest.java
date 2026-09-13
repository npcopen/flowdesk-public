package com.flowdesk.iam;

import com.flowdesk.common.authz.PermissionCode;
import com.jayway.jsonpath.JsonPath;
import com.flowdesk.common.authz.RoleCode;
import com.flowdesk.common.security.IssuedJwt;
import com.flowdesk.common.security.JwtIssuer;
import com.flowdesk.iam.repository.IamRepository.Uuids;
import com.flowdesk.iam.service.PasswordHashService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class IamApiIntegrationTest {
    @Test
    void administratorCannotRemoveTheirOwnAccessOrTheCoreAdministrationPermissions() throws Exception {
        String token=jwtIssuer.issueHuman(TENANT_A,ALICE,Set.of(RoleCode.TENANT_ADMIN),
                Set.of(PermissionCode.IAM_USER_MANAGE,PermissionCode.IAM_BINDING_MANAGE)).token();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/users/"+ALICE)
                .header("Authorization","Bearer "+token).header("If-Match","\"lv:0\"")
                .contentType("application/json").content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IAM_SELF_ACCOUNT_REQUIRED"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/users/"+ALICE+"/roles/"+ROLE_TENANT_ADMIN)
                .header("Authorization","Bearer "+token).header("If-Match","\"lv:0\"").header("Idempotency-Key","self-admin-revoke-001")
                .contentType("application/json").content("{\"state\":\"REVOKED\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IAM_SELF_ADMIN_REQUIRED"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/roles/"+ROLE_TENANT_ADMIN+"/permissions/"+permissionId(PermissionCode.IAM_BINDING_MANAGE))
                .header("Authorization","Bearer "+token).header("If-Match","\"lv:0\"").header("Idempotency-Key","core-permission-revoke-001")
                .contentType("application/json").content("{\"state\":\"REVOKED\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IAM_CORE_PERMISSION_REQUIRED"));
    }

    @Test
    void approversCanReadMinimalSameTenantIdentityWithoutAccountPermissions() throws Exception {
        String token=jwtIssuer.issueHuman(TENANT_A,DAVE,Set.of(RoleCode.APPROVER),Set.of(PermissionCode.APPROVAL_READ_ASSIGNED)).token();
        mockMvc.perform(get("/api/v1/users/"+BOB+"/identity").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(BOB.toString()))
                .andExpect(jsonPath("$.data.username").isNotEmpty()).andExpect(jsonPath("$.data.displayName").isNotEmpty())
                .andExpect(jsonPath("$.data.roleCodes").doesNotExist());
        String foreign=jwtIssuer.issueHuman(TENANT_B,DAVE,Set.of(RoleCode.APPROVER),Set.of(PermissionCode.APPROVAL_READ_ASSIGNED)).token();
        mockMvc.perform(get("/api/v1/users/"+BOB+"/identity").header("Authorization","Bearer "+foreign)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/users/"+BOB).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TENANT_DUP_1 = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final UUID TENANT_DUP_2 = UUID.fromString("33333333-3333-3333-3333-333333333332");
    private static final UUID ALICE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID BOB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID CAROL = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID DAVE = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID ERIN = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final UUID ROLE_TENANT_ADMIN = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ROLE_REQUESTER = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID ROLE_AGENT = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID ROLE_APPROVER = UUID.fromString("10000000-0000-0000-0000-000000000004");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordHashService passwordHashService;
    @Autowired
    private JwtIssuer jwtIssuer;

    private static final KeyPair KEY_PAIR = newKeyPair();

    @DynamicPropertySource
    static void jwtKeys(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.security.jwt.private-key-pem", () -> privateKeyPem());
        registry.add("flowdesk.security.jwt.public-key-pem", () -> publicKeyPem());
        registry.add("flowdesk.security.cursor-token.hmac-key", () -> cursorTokenHmacKey());
        registry.add("spring.cloud.service-registry.auto-registration.enabled", () -> "false");
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:iam-api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:schema-test.sql");
    }

    @BeforeEach
    void cleanAndSeed() {
        for (String table : List.of("audit_record", "user_role_assignment", "role_permission",
                "user_credential", "user_account", "permission", "role", "tenant")) {
            jdbc.update("DELETE FROM " + table);
        }
        insertTenant(TENANT_A, "ACME_SUPPORT", "ACME 服务台");
        insertTenant(TENANT_B, "HAIYUE", "海岳制造");
        insertTenant(TENANT_DUP_1, "DUPLICATED", "重复租户一");
        insertTenant(TENANT_DUP_2, "DUPLICATED", "重复租户二");

        insertRole(TENANT_A, ROLE_TENANT_ADMIN, "TENANT_ADMIN", "租户管理员");
        insertRole(TENANT_A, ROLE_REQUESTER, "REQUESTER", "申请人");
        insertRole(TENANT_A, ROLE_AGENT, "AGENT", "工单处理人");
        insertRole(TENANT_A, ROLE_APPROVER, "APPROVER", "审批人");

        insertPermissions(TENANT_A, Set.of(
                PermissionCode.IAM_USER_READ, PermissionCode.IAM_USER_MANAGE,
                PermissionCode.IAM_ROLE_READ, PermissionCode.IAM_ROLE_MANAGE,
                PermissionCode.IAM_PERMISSION_READ, PermissionCode.IAM_BINDING_READ,
                PermissionCode.IAM_BINDING_MANAGE, PermissionCode.TICKET_CREATE,
                PermissionCode.CATALOG_READ_ACTIVE));

        insertUser(TENANT_A, ALICE, "alice", "Alice", "ACTIVE", null);
        insertUser(TENANT_A, BOB, "bob", "Bob", "ACTIVE", null);
        insertUser(TENANT_A, CAROL, "carol", "Carol", "ACTIVE", null);
        insertUser(TENANT_A, DAVE, "dave", "Dave", "INACTIVE", null);
        insertUser(TENANT_A, ERIN, "erin", "Erin", "ACTIVE",
                Timestamp.from(Instant.now().plusSeconds(3600)));

        insertUserRole(TENANT_A, ALICE, ROLE_TENANT_ADMIN);
        insertUserRole(TENANT_A, BOB, ROLE_REQUESTER);
        insertUserRole(TENANT_A, CAROL, ROLE_AGENT);
        insertUserRole(TENANT_A, CAROL, ROLE_APPROVER);

        insertRolePermission(TENANT_A, ROLE_TENANT_ADMIN, PermissionCode.IAM_USER_READ);
        insertRolePermission(TENANT_A, ROLE_TENANT_ADMIN, PermissionCode.IAM_USER_MANAGE);
        insertRolePermission(TENANT_A, ROLE_TENANT_ADMIN, PermissionCode.IAM_ROLE_READ);
        insertRolePermission(TENANT_A, ROLE_TENANT_ADMIN, PermissionCode.IAM_ROLE_MANAGE);
        insertRolePermission(TENANT_A, ROLE_TENANT_ADMIN, PermissionCode.IAM_PERMISSION_READ);
        insertRolePermission(TENANT_A, ROLE_TENANT_ADMIN, PermissionCode.IAM_BINDING_READ);
        insertRolePermission(TENANT_A, ROLE_TENANT_ADMIN, PermissionCode.IAM_BINDING_MANAGE);
        insertRolePermission(TENANT_A, ROLE_REQUESTER, PermissionCode.TICKET_CREATE);
    }

    @Test
    void loginSucceedsAndCurrentUserComesFromJwtTenant() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .header("X-Correlation-Id", UUID.randomUUID().toString())
                        .content("""
                                {"tenantCode":"ACME_SUPPORT","username":"alice","password":"Password123!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.user.username").value("alice"))
                .andExpect(jsonPath("$.data.user.tenant.tenantCode").value("ACME_SUPPORT"))
                .andExpect(jsonPath("$.data.user.roles[0]").value("TENANT_ADMIN"))
                .andExpect(jsonPath("$.data.user.permissions").isArray());

        String token = jwtIssuer.issueHuman(TENANT_A, ALICE, Set.of(RoleCode.TENANT_ADMIN),
                Set.of(PermissionCode.IAM_USER_READ)).token();
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", TENANT_B.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenant.tenantCode").value("ACME_SUPPORT"));
    }

    @Test
    void wrongPasswordAndAmbiguousTenantCodeAreUniformLoginFailed() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"tenantCode":"ACME_SUPPORT","username":"alice","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("LOGIN_FAILED"));

        // Same tenantCode, same username and same password in two tenants: no first row shortcut.
        insertUser(TENANT_DUP_1, UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "ambig", "Ambig", "ACTIVE", null);
        insertUser(TENANT_DUP_2, UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "ambig", "Ambig", "ACTIVE", null);
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"tenantCode":"DUPLICATED","username":"ambig","password":"Password123!"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("LOGIN_FAILED"));
    }

    @Test
    void inactiveAndLockedAccountsAreRejectedAfterCredentialMatch() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"tenantCode":"ACME_SUPPORT","username":"dave","password":"Password123!"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_INACTIVE"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"tenantCode":"ACME_SUPPORT","username":"erin","password":"Password123!"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_TEMPORARILY_LOCKED"));
    }

    @Test
    void unknownTenantFieldIsRejectedAndMissingTokenIsAuthenticationRequired() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .param("tenantId", TENANT_A.toString())
                        .contentType("application/json")
                        .content("""
                                {"tenantCode":"ACME_SUPPORT","username":"alice","password":"Password123!"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TENANT_INPUT_FORBIDDEN"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"tenantCode":"ACME_SUPPORT","username":"alice","password":"Password123!","tenantId":"11111111-1111-1111-1111-111111111111"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNKNOWN_FIELD"));

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void keysetPaginationSecondPageUsesPhysicalSortColumns() throws Exception {
        String adminToken = jwtIssuer.issueHuman(TENANT_A, ALICE, Set.of(RoleCode.TENANT_ADMIN),
                Set.of(PermissionCode.IAM_USER_READ)).token();

        // Default sort is displayName (physical column u.display_name). The first page
        // returns a nextPageToken; the second page must not fail with an unknown column
        // because the cursor predicate reuses the mapped physical column.
        String firstPage = mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn().getResponse().getContentAsString();

        String nextToken = new tools.jackson.databind.ObjectMapper()
                .readTree(firstPage).path("meta").path("page").path("nextPageToken").asText();
        assertThat(nextToken).isNotEmpty();

        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("pageSize", "2")
                        .param("pageToken", nextToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        // Username sort path also uses a mapped physical column in the cursor predicate.
        String usernamePage = mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("pageSize", "2")
                        .param("sort", "username"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String usernameToken = new tools.jackson.databind.ObjectMapper()
                .readTree(usernamePage).path("meta").path("page").path("nextPageToken").asText();
        assertThat(usernameToken).isNotEmpty();
        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("pageSize", "2")
                        .param("sort", "username")
                        .param("pageToken", usernameToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void tenantAdminCanCreateListAndPatchUserButRequesterCannotReadUsers() throws Exception {
        String requesterToken = jwtIssuer.issueHuman(TENANT_A, BOB, Set.of(RoleCode.REQUESTER),
                Set.of(PermissionCode.TICKET_CREATE)).token();
        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));

        String adminToken = jwtIssuer.issueHuman(TENANT_A, ALICE, Set.of(RoleCode.TENANT_ADMIN),
                Set.of(PermissionCode.IAM_USER_READ, PermissionCode.IAM_USER_MANAGE)).token();
        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        String key = "user-create-000000000001";
        String body = """
                {"username":"frank","displayName":"Frank","initialPassword":"Password123!","status":"ACTIVE"}
                """;
        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.data.username").value("frank"));

        // Same key + same intent replays the created resource without applying the password again.
        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.data.username").value("frank"));

        String createdJson = mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("roleCode", "TENANT_ADMIN"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // Use the known admin list to find Alice ETag for a patch instead of parsing Frank.
        mockMvc.perform(patch("/api/v1/users/" + ALICE)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("If-Match", "\"lv:0\"")
                        .contentType("application/json")
                        .content("""
                                {"displayName":"Alice Updated"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Alice Updated"))
                .andExpect(jsonPath("$.data.lockVersion").value(1));
    }

    @Test
    void concurrentUserCreateReplaysOneAppendOnlyAuditEffect() throws Exception {
        String adminToken = jwtIssuer.issueHuman(TENANT_A, ALICE, Set.of(RoleCode.TENANT_ADMIN),
                Set.of(PermissionCode.IAM_USER_MANAGE)).token();
        String key = "concurrent-user-create-0001";
        String body = """
                {"username":"concurrent-user","displayName":"Concurrent User","initialPassword":"Password123!","status":"ACTIVE"}
                """;
        int requestCount = 12;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<MvcResult>> futures = java.util.stream.IntStream.range(0, requestCount)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                        return mockMvc.perform(post("/api/v1/users")
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", key)
                                        .contentType("application/json")
                                        .content(body))
                                .andReturn();
                    }))
                    .toList();

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<MvcResult> results = new java.util.ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(results).allSatisfy(result ->
                    assertThat(result.getResponse().getStatus()).isIn(200, 201));
            assertThat(results.stream()
                    .filter(result -> result.getResponse().getStatus() == 201)
                    .count()).isEqualTo(1);
            assertThat(results.stream()
                    .filter(result -> result.getResponse().getStatus() == 200)
                    .count()).isEqualTo(requestCount - 1L);
            assertThat(results.stream()
                    .filter(result -> result.getResponse().getStatus() == 200))
                    .allSatisfy(result -> assertThat(
                            result.getResponse().getHeader("Idempotency-Replayed"))
                            .isEqualTo("true"));

            Set<String> userIds = new java.util.HashSet<>();
            for (MvcResult result : results) {
                userIds.add(JsonPath.read(
                        result.getResponse().getContentAsString(), "$.data.userId"));
            }
            assertThat(userIds).hasSize(1);
            UUID userId = UUID.fromString(userIds.iterator().next());

            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM user_account
                    WHERE tenant_id = ? AND user_id = ? AND username = ?
                    """, Integer.class, Uuids.toBytes(TENANT_A), Uuids.toBytes(userId),
                    "concurrent-user")).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM user_credential
                    WHERE tenant_id = ? AND user_id = ?
                    """, Integer.class, Uuids.toBytes(TENANT_A), Uuids.toBytes(userId)))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM audit_record
                    WHERE tenant_id = ? AND audit_id = ? AND action_code = 'IAM_USER_CREATE'
                      AND outcome = 'SUCCEEDED'
                    """, Integer.class, Uuids.toBytes(TENANT_A), Uuids.toBytes(userId)))
                    .isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void unrelatedAuditConstraintRollsBackUserCreateAndRetryCanSucceed() throws Exception {
        String adminToken = jwtIssuer.issueHuman(TENANT_A, ALICE, Set.of(RoleCode.TENANT_ADMIN),
                Set.of(PermissionCode.IAM_USER_MANAGE)).token();
        String key = "user-audit-conflict-0001";
        String body = """
                {"username":"audit-conflict-user","displayName":"Audit Conflict User","initialPassword":"Password123!","status":"ACTIVE"}
                """;
        insertAuditAnchor("IAM_USER_CREATE");
        jdbc.execute("ALTER TABLE audit_record ADD CONSTRAINT uk_test_iam_user_action UNIQUE (action_code)");
        try {
            mockMvc.perform(post("/api/v1/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", key)
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_account WHERE username = ?",
                    Integer.class, "audit-conflict-user")).isZero();
        } finally {
            jdbc.execute("ALTER TABLE audit_record DROP CONSTRAINT uk_test_iam_user_action");
        }

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotency-Replayed"));
    }

    @Test
    void unrelatedAuditConstraintRollsBackRoleCreateAndRetryCanSucceed() throws Exception {
        String adminToken = jwtIssuer.issueHuman(TENANT_B, ALICE, Set.of(RoleCode.TENANT_ADMIN),
                Set.of(PermissionCode.IAM_ROLE_MANAGE)).token();
        String key = "role-audit-conflict-0001";
        String body = """
                {"roleCode":"REQUESTER","roleName":"海岳申请人","status":"ACTIVE"}
                """;
        insertAuditAnchor("IAM_ROLE_CREATE");
        jdbc.execute("ALTER TABLE audit_record ADD CONSTRAINT uk_test_iam_role_action UNIQUE (action_code)");
        try {
            mockMvc.perform(post("/api/v1/roles")
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", key)
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM role
                    WHERE tenant_id = ? AND role_code = 'REQUESTER'
                    """, Integer.class, Uuids.toBytes(TENANT_B))).isZero();
        } finally {
            jdbc.execute("ALTER TABLE audit_record DROP CONSTRAINT uk_test_iam_role_action");
        }

        mockMvc.perform(post("/api/v1/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotency-Replayed"));
    }

    @Test
    void unrelatedAuditConstraintRollsBackBindingCreateAndRetryCanSucceed() throws Exception {
        String adminToken = jwtIssuer.issueHuman(TENANT_A, ALICE, Set.of(RoleCode.TENANT_ADMIN),
                Set.of(PermissionCode.IAM_BINDING_MANAGE)).token();
        UUID permissionId = permissionId(PermissionCode.CATALOG_READ_ACTIVE);
        String key = "binding-audit-conflict-0001";
        String body = """
                {"state":"GRANTED"}
                """;
        insertAuditAnchor("IAM_ROLE_PERMISSION_PUT");
        jdbc.execute("ALTER TABLE audit_record ADD CONSTRAINT uk_test_iam_binding_action UNIQUE (action_code)");
        try {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .put("/api/v1/roles/{roleId}/permissions/{permissionId}", ROLE_REQUESTER, permissionId)
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", key)
                            .header("If-None-Match", "*")
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM role_permission
                    WHERE tenant_id = ? AND role_id = ? AND permission_id = ?
                    """, Integer.class, Uuids.toBytes(TENANT_A), Uuids.toBytes(ROLE_REQUESTER),
                    Uuids.toBytes(permissionId))).isZero();
        } finally {
            jdbc.execute("ALTER TABLE audit_record DROP CONSTRAINT uk_test_iam_binding_action");
        }

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/roles/{roleId}/permissions/{permissionId}", ROLE_REQUESTER, permissionId)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", key)
                        .header("If-None-Match", "*")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotency-Replayed"));
    }
    @Test
    void unrelatedAuditConstraintRollsBackUserPatchAndRetryCanSucceed() throws Exception {
        String adminToken = jwtIssuer.issueHuman(TENANT_A, ALICE, Set.of(RoleCode.TENANT_ADMIN),
                Set.of(PermissionCode.IAM_USER_MANAGE)).token();
        insertAuditAnchor("IAM_USER_UPDATE");
        jdbc.execute("ALTER TABLE audit_record ADD CONSTRAINT uk_test_iam_user_update_action UNIQUE (action_code)");
        try {
            mockMvc.perform(patch("/api/v1/users/" + BOB)
                            .header("Authorization", "Bearer " + adminToken)
                            .header("If-Match", "\"lv:0\"")
                            .contentType("application/json")
                            .content("""
                                    {"displayName":"Rolled Back User"}
                                    """))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
            assertThat(jdbc.queryForObject("""
                    SELECT display_name FROM user_account
                    WHERE tenant_id = ? AND user_id = ?
                    """, String.class, Uuids.toBytes(TENANT_A), Uuids.toBytes(BOB)))
                    .isEqualTo("Bob");
            assertThat(jdbc.queryForObject("""
                    SELECT lock_version FROM user_account
                    WHERE tenant_id = ? AND user_id = ?
                    """, Long.class, Uuids.toBytes(TENANT_A), Uuids.toBytes(BOB)))
                    .isZero();
        } finally {
            jdbc.execute("ALTER TABLE audit_record DROP CONSTRAINT uk_test_iam_user_update_action");
        }

        mockMvc.perform(patch("/api/v1/users/" + BOB)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("If-Match", "\"lv:0\"")
                        .contentType("application/json")
                        .content("""
                                {"displayName":"Rolled Back User"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Rolled Back User"))
                .andExpect(jsonPath("$.data.lockVersion").value(1));
    }

    @Test
    void unrelatedAuditConstraintRollsBackRolePatchAndRetryCanSucceed() throws Exception {
        String adminToken = jwtIssuer.issueHuman(TENANT_A, ALICE, Set.of(RoleCode.TENANT_ADMIN),
                Set.of(PermissionCode.IAM_ROLE_MANAGE)).token();
        insertAuditAnchor("IAM_ROLE_UPDATE");
        jdbc.execute("ALTER TABLE audit_record ADD CONSTRAINT uk_test_iam_role_update_action UNIQUE (action_code)");
        try {
            mockMvc.perform(patch("/api/v1/roles/" + ROLE_REQUESTER)
                            .header("Authorization", "Bearer " + adminToken)
                            .header("If-Match", "\"lv:0\"")
                            .contentType("application/json")
                            .content("""
                                    {"roleName":"回滚申请人"}
                                    """))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
            assertThat(jdbc.queryForObject("""
                    SELECT role_name FROM role
                    WHERE tenant_id = ? AND role_id = ?
                    """, String.class, Uuids.toBytes(TENANT_A), Uuids.toBytes(ROLE_REQUESTER)))
                    .isEqualTo("申请人");
            assertThat(jdbc.queryForObject("""
                    SELECT lock_version FROM role
                    WHERE tenant_id = ? AND role_id = ?
                    """, Long.class, Uuids.toBytes(TENANT_A), Uuids.toBytes(ROLE_REQUESTER)))
                    .isZero();
        } finally {
            jdbc.execute("ALTER TABLE audit_record DROP CONSTRAINT uk_test_iam_role_update_action");
        }

        mockMvc.perform(patch("/api/v1/roles/" + ROLE_REQUESTER)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("If-Match", "\"lv:0\"")
                        .contentType("application/json")
                        .content("""
                                {"roleName":"回滚申请人"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleName").value("回滚申请人"))
                .andExpect(jsonPath("$.data.lockVersion").value(1));
    }

    @Test
    void unrelatedAuditConstraintRollsBackBindingUpdateAndRetryCanSucceed() throws Exception {
        String adminToken = jwtIssuer.issueHuman(TENANT_A, ALICE, Set.of(RoleCode.TENANT_ADMIN),
                Set.of(PermissionCode.IAM_BINDING_MANAGE)).token();
        insertAuditAnchor("IAM_USER_ROLE_PUT");
        jdbc.execute("ALTER TABLE audit_record ADD CONSTRAINT uk_test_iam_user_role_update_action UNIQUE (action_code)");
        try {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .put("/api/v1/users/{userId}/roles/{roleId}", BOB, ROLE_REQUESTER)
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", "binding-update-audit-conflict-0001")
                            .header("If-Match", "\"lv:0\"")
                            .contentType("application/json")
                            .content("""
                                    {"state":"REVOKED"}
                                    """))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
            assertThat(jdbc.queryForObject("""
                    SELECT state FROM user_role_assignment
                    WHERE tenant_id = ? AND user_id = ? AND role_id = ?
                    """, String.class, Uuids.toBytes(TENANT_A), Uuids.toBytes(BOB),
                    Uuids.toBytes(ROLE_REQUESTER))).isEqualTo("GRANTED");
            assertThat(jdbc.queryForObject("""
                    SELECT lock_version FROM user_role_assignment
                    WHERE tenant_id = ? AND user_id = ? AND role_id = ?
                    """, Long.class, Uuids.toBytes(TENANT_A), Uuids.toBytes(BOB),
                    Uuids.toBytes(ROLE_REQUESTER))).isZero();
        } finally {
            jdbc.execute("ALTER TABLE audit_record DROP CONSTRAINT uk_test_iam_user_role_update_action");
        }

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/users/{userId}/roles/{roleId}", BOB, ROLE_REQUESTER)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", "binding-update-audit-conflict-0001")
                        .header("If-Match", "\"lv:0\"")
                        .contentType("application/json")
                        .content("""
                                {"state":"REVOKED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("REVOKED"))
                .andExpect(jsonPath("$.data.lockVersion").value(1));
    }
    @Test
    void tenantAdminCanBindAndRevokeRolesWithCeilingEnforcement() throws Exception {
        String adminToken = jwtIssuer.issueHuman(TENANT_A, ALICE, Set.of(RoleCode.TENANT_ADMIN),
                Set.of(PermissionCode.IAM_ROLE_READ, PermissionCode.IAM_BINDING_READ,
                        PermissionCode.IAM_BINDING_MANAGE, PermissionCode.IAM_PERMISSION_READ)).token();
        UUID ticketCreatePermissionId = jdbc.queryForObject(
                "SELECT permission_id FROM permission WHERE tenant_id = ? AND permission_code = ?",
                (rs, i) -> Uuids.toUuid(rs.getBytes(1)),
                Uuids.toBytes(TENANT_A), PermissionCode.TICKET_CREATE.name());
        UUID catalogReadPermissionId = jdbc.queryForObject(
                "SELECT permission_id FROM permission WHERE tenant_id = ? AND permission_code = ?",
                (rs, i) -> Uuids.toUuid(rs.getBytes(1)),
                Uuids.toBytes(TENANT_A), PermissionCode.CATALOG_READ_ACTIVE.name());

        // Valid ceiling: REQUESTER role + TICKET_CREATE.
        String key = "bind-requester-ticket-0000000001";
        String body = """
                {"state":"GRANTED"}
                """;
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/roles/{roleId}/permissions/{permissionId}",
                                ROLE_REQUESTER, catalogReadPermissionId)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", key)
                        .header("If-None-Match", "*")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.state").value("GRANTED"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/roles/{roleId}/permissions/{permissionId}",
                                ROLE_REQUESTER, catalogReadPermissionId)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", key)
                        .header("If-None-Match", "*")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"));

        mockMvc.perform(get("/api/v1/roles/{roleId}/permissions", ROLE_REQUESTER)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        // TenantAdmin role ceiling does not allow TICKET_CREATE.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/roles/{roleId}/permissions/{permissionId}",
                                ROLE_TENANT_ADMIN, ticketCreatePermissionId)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", "bind-admin-ticket-0000000001")
                        .header("If-None-Match", "*")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("PERMISSION_CEILING_VIOLATION"));

        // User-role revocation updates the existing GRANTED row under If-Match.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/users/{userId}/roles/{roleId}", BOB, ROLE_REQUESTER)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", "revoke-bob-requester-00000001")
                        .header("If-Match", "\"lv:0\"")
                        .contentType("application/json")
                        .content("""
                                {"state":"REVOKED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("REVOKED"));
    }

    @Test
    void sameStateBindingPutReturnsOkEvenWhenLockVersionIsStillZero() throws Exception {
        String adminToken = jwtIssuer.issueHuman(TENANT_A, ALICE, Set.of(RoleCode.TENANT_ADMIN),
                Set.of(PermissionCode.IAM_BINDING_MANAGE)).token();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/users/{userId}/roles/{roleId}", BOB, ROLE_REQUESTER)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", "same-state-bob-requester-00001")
                        .header("If-Match", "\"lv:0\"")
                        .contentType("application/json")
                        .content("""
                                {"state":"GRANTED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andExpect(jsonPath("$.data.state").value("GRANTED"))
                .andExpect(jsonPath("$.data.lockVersion").value(0));
    }

    @Test
    void bindingListsPaginateByCodeInBothDirections() throws Exception {
        String adminToken = jwtIssuer.issueHuman(TENANT_A, ALICE, Set.of(RoleCode.TENANT_ADMIN),
                Set.of(PermissionCode.IAM_BINDING_READ)).token();
        insertRolePermission(TENANT_A, ROLE_REQUESTER, PermissionCode.CATALOG_READ_ACTIVE);
        insertRolePermission(TENANT_A, ROLE_REQUESTER, PermissionCode.IAM_BINDING_READ);
        UUID catalogId = permissionId(PermissionCode.CATALOG_READ_ACTIVE);
        UUID bindingReadId = permissionId(PermissionCode.IAM_BINDING_READ);
        UUID ticketCreateId = permissionId(PermissionCode.TICKET_CREATE);

        String firstAscending = mockMvc.perform(get("/api/v1/roles/{roleId}/permissions", ROLE_REQUESTER)
                        .header("Authorization", "Bearer " + adminToken)
                        .param("pageSize", "2")
                        .param("sort", "permissionCode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].targetId").value(catalogId.toString()))
                .andExpect(jsonPath("$.data[1].targetId").value(bindingReadId.toString()))
                .andReturn().getResponse().getContentAsString();
        String ascendingToken = JsonPath.read(firstAscending, "$.meta.page.nextPageToken");
        mockMvc.perform(get("/api/v1/roles/{roleId}/permissions", ROLE_REQUESTER)
                        .header("Authorization", "Bearer " + adminToken)
                        .param("pageSize", "2")
                        .param("sort", "permissionCode")
                        .param("pageToken", ascendingToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].targetId").value(ticketCreateId.toString()));

        String firstDescending = mockMvc.perform(get("/api/v1/roles/{roleId}/permissions", ROLE_REQUESTER)
                        .header("Authorization", "Bearer " + adminToken)
                        .param("pageSize", "2")
                        .param("sort", "-permissionCode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].targetId").value(ticketCreateId.toString()))
                .andExpect(jsonPath("$.data[1].targetId").value(bindingReadId.toString()))
                .andReturn().getResponse().getContentAsString();
        String descendingToken = JsonPath.read(firstDescending, "$.meta.page.nextPageToken");
        mockMvc.perform(get("/api/v1/roles/{roleId}/permissions", ROLE_REQUESTER)
                        .header("Authorization", "Bearer " + adminToken)
                        .param("pageSize", "2")
                        .param("sort", "-permissionCode")
                        .param("pageToken", descendingToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].targetId").value(catalogId.toString()));
    }

    @Test
    void expiredLoginLockResetsFailureCounterAfterAnotherWrongPassword() throws Exception {
        jdbc.update("""
                UPDATE user_credential
                SET failed_attempt_count = 5,
                    locked_until = ?
                WHERE tenant_id = ? AND user_id = ?
                """, Timestamp.from(Instant.now().minusSeconds(60)),
                Uuids.toBytes(TENANT_A), Uuids.toBytes(BOB));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"tenantCode":"ACME_SUPPORT","username":"bob","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("LOGIN_FAILED"));

        Integer failedCount = jdbc.queryForObject("""
                SELECT failed_attempt_count FROM user_credential
                WHERE tenant_id = ? AND user_id = ?
                """, Integer.class, Uuids.toBytes(TENANT_A), Uuids.toBytes(BOB));
        Timestamp lockedUntil = jdbc.queryForObject("""
                SELECT locked_until FROM user_credential
                WHERE tenant_id = ? AND user_id = ?
                """, Timestamp.class, Uuids.toBytes(TENANT_A), Uuids.toBytes(BOB));
        assertThat(failedCount).isEqualTo(1);
        assertThat(lockedUntil).isNull();
    }

    @Test
    void duplicateQueryParametersAreRejected() throws Exception {
        String adminToken = jwtIssuer.issueHuman(TENANT_A, ALICE, Set.of(RoleCode.TENANT_ADMIN),
                Set.of(PermissionCode.IAM_ROLE_READ)).token();

        mockMvc.perform(get("/api/v1/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "ACTIVE", "INACTIVE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_QUERY_PARAMETER"));
    }

    @Test
    void serviceEligibilityEnforcesTripleAndTenantScope() throws Exception {
        IssuedJwt ticketJwt = jwtIssuer.issueService(TENANT_A, "flowdesk-ticket-service");
        String correlation = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/internal/iam/eligibility-checks")
                        .header("Authorization", "Bearer " + ticketJwt.token())
                        .header("X-Correlation-Id", correlation)
                        .contentType("application/json")
                        .content("""
                                {"subjectUserId":"cccccccc-cccc-cccc-cccc-cccccccccccc","requiredRoleCode":"AGENT","purpose":"TICKET_ASSIGNEE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligible").value(true))
                .andExpect(jsonPath("$.data.reasonCode").value("ELIGIBLE"));

        mockMvc.perform(post("/api/v1/internal/iam/eligibility-checks")
                        .header("Authorization", "Bearer " + ticketJwt.token())
                        .header("X-Correlation-Id", correlation)
                        .contentType("application/json")
                        .content("""
                                {"subjectUserId":"cccccccc-cccc-cccc-cccc-cccccccccccc","requiredRoleCode":"APPROVER","purpose":"TICKET_ASSIGNEE"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ELIGIBILITY_PURPOSE_FORBIDDEN"));

        mockMvc.perform(post("/api/v1/internal/iam/eligibility-checks")
                        .header("Authorization", "Bearer " + ticketJwt.token())
                        .header("X-Correlation-Id", correlation)
                        .contentType("application/json")
                        .content("""
                                {"subjectUserId":"22222222-2222-2222-2222-222222222222","requiredRoleCode":"AGENT","purpose":"TICKET_ASSIGNEE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligible").value(false))
                .andExpect(jsonPath("$.data.reasonCode").value("NOT_FOUND"));

        String humanToken = jwtIssuer.issueHuman(TENANT_A, ALICE, Set.of(RoleCode.TENANT_ADMIN),
                Set.of(PermissionCode.IAM_USER_READ)).token();
        mockMvc.perform(post("/api/v1/internal/iam/eligibility-checks")
                        .header("Authorization", "Bearer " + humanToken)
                        .header("X-Correlation-Id", correlation)
                        .contentType("application/json")
                        .content("""
                                {"subjectUserId":"cccccccc-cccc-cccc-cccc-cccccccccccc","requiredRoleCode":"AGENT","purpose":"TICKET_ASSIGNEE"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("SERVICE_IDENTITY_REQUIRED"));
    }


    @Test
    void invalidOrEmptyPageTokenFailsClosedWithCursorInvalid() throws Exception {
        String adminToken = jwtIssuer.issueHuman(TENANT_A, ALICE, Set.of(RoleCode.TENANT_ADMIN),
                Set.of(PermissionCode.IAM_USER_READ)).token();

        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("pageToken", "tampered-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CURSOR_INVALID"));

        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("pageToken", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_QUERY_PARAMETER"));
    }

    @Test
    void existingBindingWithIfNoneMatchStarReturnsResourcePreconditionFailed() throws Exception {
        String adminToken = jwtIssuer.issueHuman(TENANT_A, ALICE, Set.of(RoleCode.TENANT_ADMIN),
                Set.of(PermissionCode.IAM_BINDING_MANAGE)).token();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/users/{userId}/roles/{roleId}", BOB, ROLE_REQUESTER)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", "existing-precondition-0000001")
                        .header("If-None-Match", "*")
                        .contentType("application/json")
                        .content("""
                                {"state":"GRANTED"}
                                """))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_PRECONDITION_FAILED"));
    }

    @Test
    void unknownQueryParamsAndDuplicateJsonKeysAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .param("unexpected", "value")
                        .contentType("application/json")
                        .content("""
                                {"tenantCode":"ACME_SUPPORT","username":"alice","password":"Password123!"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_QUERY_PARAMETER"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"tenantCode":"ACME_SUPPORT","tenantCode":"HAIYUE","username":"alice","password":"Password123!"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_JSON"));
    }

    @Test
    void invalidRoleCodeAndBlankPatchFieldsAreValidated() throws Exception {
        String adminToken = jwtIssuer.issueHuman(TENANT_A, ALICE, Set.of(RoleCode.TENANT_ADMIN),
                Set.of(PermissionCode.IAM_ROLE_MANAGE, PermissionCode.IAM_USER_MANAGE)).token();

        mockMvc.perform(post("/api/v1/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", "invalid-role-code-000000001")
                        .contentType("application/json")
                        .content("""
                                {"roleCode":"SUPER_ADMIN","roleName":"Super","status":"ACTIVE"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ROLE_CODE_NOT_ALLOWED"));

        mockMvc.perform(patch("/api/v1/users/" + ALICE)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("If-Match", "\"lv:0\"")
                        .contentType("application/json")
                        .content("""
                                {"displayName":"   "}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void controlCharactersAreRejectedAndInstantsUseFixedSixFractionDigits() throws Exception {
        String backslash = "\\";
        String controlCharBody = "{\"tenantCode\":\"ACME_SUPPORT\","
                + "\"username\":\"alice" + backslash + "u0001\","
                + "\"password\":\"Password123!\"}";
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(controlCharBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        String login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"tenantCode":"ACME_SUPPORT","username":"alice","password":"Password123!"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String expiresAt = new tools.jackson.databind.ObjectMapper()
                .readTree(login).path("data").path("expiresAt").asText();
        assertThat(Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z")
                .matcher(expiresAt).matches()).isTrue();
    }

    @Test
    void unknownPathsAndMalformedUuidUseTheUnifiedErrorEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/not-a-real-path"))
                .andExpect(status().isUnauthorized());

        String adminToken = jwtIssuer.issueHuman(TENANT_A, ALICE, Set.of(RoleCode.TENANT_ADMIN),
                Set.of(PermissionCode.IAM_USER_READ)).token();
        mockMvc.perform(get("/api/v1/definitely-not-found")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/users/not-a-uuid")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ----- helpers ------------------------------------------------------------------

    private void insertTenant(UUID tenantId, String code, String displayName) {
        jdbc.update("""
                INSERT INTO tenant (tenant_id, tenant_code, display_name, created_by_type, updated_by_type)
                VALUES (?, ?, ?, 'SERVICE', 'SERVICE')
                """, Uuids.toBytes(tenantId), code, displayName);
    }

    private void insertRole(UUID tenantId, UUID roleId, String code, String name) {
        jdbc.update("""
                INSERT INTO role (tenant_id, role_id, role_code, role_name, status,
                                  created_by_type, updated_by_type)
                VALUES (?, ?, ?, ?, 'ACTIVE', 'SERVICE', 'SERVICE')
                """, Uuids.toBytes(tenantId), Uuids.toBytes(roleId), code, name);
    }

    private void insertPermissions(UUID tenantId, Set<PermissionCode> codes) {
        for (PermissionCode code : codes) {
            jdbc.update("""
                    INSERT INTO permission (tenant_id, permission_id, permission_code, permission_name,
                                            created_by_type)
                    VALUES (?, ?, ?, ?, 'SERVICE')
                    """, Uuids.toBytes(tenantId), Uuids.toBytes(UUID.randomUUID()),
                    code.name(), code.name());
        }
    }

    private void insertUser(UUID tenantId, UUID userId, String username, String displayName,
                            String status, Timestamp lockedUntil) {
        jdbc.update("""
                INSERT INTO user_account (tenant_id, user_id, username, display_name, status,
                                          created_by_type, updated_by_type)
                VALUES (?, ?, ?, ?, ?, 'SERVICE', 'SERVICE')
                """, Uuids.toBytes(tenantId), Uuids.toBytes(userId), username, displayName, status);
        jdbc.update("""
                INSERT INTO user_credential (tenant_id, user_id, password_hash, password_hash_scheme,
                                             failed_attempt_count, locked_until, password_changed_at,
                                             created_by_type, updated_by_type)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6), 'SERVICE', 'SERVICE')
                """, Uuids.toBytes(tenantId), Uuids.toBytes(userId),
                passwordHashService.encode("Password123!"), PasswordHashService.SCHEME,
                lockedUntil == null ? 0 : 1, lockedUntil);
    }

    private void insertUserRole(UUID tenantId, UUID userId, UUID roleId) {
        jdbc.update("""
                INSERT INTO user_role_assignment (tenant_id, user_id, role_id, state,
                                                  granted_at, granted_by_type)
                VALUES (?, ?, ?, 'GRANTED', CURRENT_TIMESTAMP(6), 'SERVICE')
                """, Uuids.toBytes(tenantId), Uuids.toBytes(userId), Uuids.toBytes(roleId));
    }

    private void insertAuditAnchor(String actionCode) {
        jdbc.update("""
                INSERT INTO audit_record
                    (tenant_id, audit_id, action_code, target_type, target_id, outcome,
                     actor_type, actor_id, reason, error_code, correlation_id, causation_id,
                     occurred_at, recorded_at)
                VALUES (?, ?, ?, 'TEST', ?, 'SUCCEEDED', 'SERVICE', ?, 'anchor', NULL, ?, NULL,
                        CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, Uuids.toBytes(TENANT_A), Uuids.toBytes(UUID.randomUUID()), actionCode,
                Uuids.toBytes(UUID.randomUUID()), Uuids.toBytes(ALICE), Uuids.toBytes(UUID.randomUUID()));
    }
    private UUID permissionId(PermissionCode code) {
        return jdbc.queryForObject(
                "SELECT permission_id FROM permission WHERE tenant_id = ? AND permission_code = ?",
                (rs, i) -> Uuids.toUuid(rs.getBytes(1)),
                Uuids.toBytes(TENANT_A), code.name());
    }

    private void insertRolePermission(UUID tenantId, UUID roleId, PermissionCode code) {
        UUID permissionId = jdbc.queryForObject(
                "SELECT permission_id FROM permission WHERE tenant_id = ? AND permission_code = ?",
                (rs, i) -> Uuids.toUuid(rs.getBytes(1)),
                Uuids.toBytes(tenantId), code.name());
        jdbc.update("""
                INSERT INTO role_permission (tenant_id, role_id, permission_id, state,
                                             granted_at, granted_by_type)
                VALUES (?, ?, ?, 'GRANTED', CURRENT_TIMESTAMP(6), 'SERVICE')
                """, Uuids.toBytes(tenantId), Uuids.toBytes(roleId), Uuids.toBytes(permissionId));
    }

    private static String cursorTokenHmacKey() {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 0x5A);
        return Base64.getEncoder().encodeToString(key);
    }

    private static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("RSA").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String privateKeyPem() {
        String nl = System.lineSeparator();
        String base64 = Base64.getEncoder().encodeToString(KEY_PAIR.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----" + nl + base64 + nl + "-----END PRIVATE KEY-----";
    }

    private static String publicKeyPem() {
        String nl = System.lineSeparator();
        String base64 = Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----" + nl + base64 + nl + "-----END PUBLIC KEY-----";
    }

    private static String readResource(String name) {
        try (var in = IamApiIntegrationTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
