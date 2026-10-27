package com.flowdesk.ticket;

import com.flowdesk.ticket.mapper.*;
import com.flowdesk.ticket.model.*;
import com.flowdesk.ticket.persistence.Uuids;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.RoleCode;
import com.flowdesk.common.security.IssuedJwt;
import com.flowdesk.common.security.JwtIssuer;
import com.flowdesk.ticket.iam.AssigneeEligibilityChecker;
import com.flowdesk.ticket.event.TicketEventContract;
import com.flowdesk.ticket.event.WorkflowStartV1PayloadCodec;
import com.flowdesk.ticket.persistence.Uuids;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
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
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工单模块回归测试，覆盖该组件的正常流程、边界条件与关键一致性约束。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TicketApiIntegrationTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID REQUESTER_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AGENT_A = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID AGENT_A2 = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID INELIGIBLE = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final UUID ADMIN_A = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID REQUESTER_B = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    private static final UUID SLA_A = UUID.fromString("5aa00000-0000-0000-0000-000000000001");
    private static final UUID SLA_INACTIVE = UUID.fromString("5aa00000-0000-0000-0000-000000000010");
    private static final UUID CATALOG_INACTIVE_POLICY = UUID.fromString("5aa00000-0000-0000-0000-000000000011");
    private static final UUID CATALOG_NORMAL = UUID.fromString("5aa00000-0000-0000-0000-000000000002");
    private static final UUID CATALOG_HIGH = UUID.fromString("5aa00000-0000-0000-0000-000000000003");
    private static final UUID CATALOG_INACTIVE = UUID.fromString("5aa00000-0000-0000-0000-000000000004");
    private static final UUID WORKFLOW_DEF = UUID.fromString("5aa00000-0000-0000-0000-000000000005");
    private static final UUID WORKFLOW_VERSION = UUID.fromString("5aa00000-0000-0000-0000-000000000006");

    private static final KeyPair KEY_PAIR = newKeyPair();
    private static final JwtIssuer ISSUER = new JwtIssuer(KEY_PAIR.getPrivate(),
            "flowdesk-iam-service", "flowdesk-api", "flowdesk-internal", 900, 300);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.flowdesk.ticket.approval.ApprovalAccessChecker approvalAccessChecker;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.flowdesk.ticket.catalog.CatalogVersionClient catalogVersionClient;

    /**
     * 验证混合角色能读本人和分派范围，不遗漏本人申请。
     */
    @Test
    void mixedRoleReadsOwnAndAssignedScopesWithoutLosingOwnRequests() throws Exception {
        UUID id=createTicket(CATALOG_NORMAL,"mixed-role-own-ticket");
        String mixed=ISSUER.issueHuman(TENANT_A,REQUESTER_A,Set.of(RoleCode.REQUESTER,RoleCode.AGENT),
                Set.of(PermissionCode.TICKET_READ_OWN,PermissionCode.TICKET_READ_ASSIGNED)).token();
        for(String scope:List.of("visible","mine")) mockMvc.perform(get("/api/v1/tickets")
                .header("Authorization","Bearer "+mixed).param("scope",scope))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].ticketId").value(id.toString()));
        mockMvc.perform(get("/api/v1/tickets/summary").header("Authorization","Bearer "+mixed))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pendingCount").value(1));
        mockMvc.perform(get("/api/v1/tickets").header("Authorization","Bearer "+mixed).param("scope","tenant"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/tickets").header("Authorization","Bearer "+mixed).param("scope","assigned"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    /**
     * 验证管理员可新建目录并绑定规则，新规则仅影响未来工单。
     */
    @Test
    void administratorsCreateCatalogTypesAndBindNewRulesOnlyForFutureTickets() throws Exception {
        String normal="""
                {"itemCode":"MONITOR_REQUEST","itemName":"外接显示器","description":"办公设备",
                 "riskLevel":"NORMAL","slaPolicyId":"%s","workflowRef":null,"status":"ACTIVE"}
                """.formatted(SLA_A);
        mockMvc.perform(post("/api/v1/service-catalog-items").header("Authorization","Bearer "+requesterToken())
                .header("Idempotency-Key","catalog-create-normal-01").contentType("application/json").content(normal)).andExpect(status().isForbidden());
        var created=mockMvc.perform(post("/api/v1/service-catalog-items").header("Authorization","Bearer "+adminToken())
                .header("Idempotency-Key","catalog-create-normal-01").contentType("application/json").content(normal))
                .andExpect(status().isCreated()).andReturn();
        UUID catalogId=UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(),"$.data.catalogItemId"));
        mockMvc.perform(post("/api/v1/service-catalog-items").header("Authorization","Bearer "+adminToken())
                .header("Idempotency-Key","catalog-create-normal-01").contentType("application/json").content(normal)).andExpect(status().isOk());
        createTicket(catalogId,"new-catalog-real-ticket");
        UUID oldTicket=createTicket(CATALOG_HIGH,"old-workflow-bound-ticket");
        UUID newVersion=UUID.randomUUID();
        String high="""
                {"itemCode":"PROD_DB_ACCESS","itemName":"生产库访问","description":"访问申请",
                 "riskLevel":"HIGH","slaPolicyId":"%s","workflowRef":{"definitionId":"%s","workflowVersionId":"%s","versionNo":2},"status":"ACTIVE"}
                """.formatted(SLA_A,WORKFLOW_DEF,newVersion);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/service-catalog-items/"+CATALOG_HIGH+"/configuration")
                .header("Authorization","Bearer "+adminToken()).header("If-Match","\"lv:0\"").contentType("application/json").content(high)).andExpect(status().isOk());
        UUID newTicket=createTicket(CATALOG_HIGH,"new-workflow-bound-ticket");
        mockMvc.perform(get("/api/v1/tickets/"+oldTicket).header("Authorization","Bearer "+requesterToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.workflowRef.workflowVersionId").value(WORKFLOW_VERSION.toString()));
        mockMvc.perform(get("/api/v1/tickets/"+newTicket).header("Authorization","Bearer "+requesterToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.workflowRef.workflowVersionId").value(newVersion.toString()));
        org.mockito.Mockito.verify(catalogVersionClient).validate(org.mockito.ArgumentMatchers.argThat(w->w.workflowVersionId().equals(newVersion)),org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * 验证工作台统计覆盖所有可见工单，不限第一页。
     */
    @Test
    void dashboardCountsAllVisibleTicketsBeyondTheFirstPage() throws Exception {
        for (int i = 0; i < 11; i++) createTicket(CATALOG_NORMAL, "summary-ticket-" + i);
        mockMvc.perform(get("/api/v1/tickets/summary").header("Authorization", "Bearer " + requesterToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pendingCount").value(11));
        mockMvc.perform(get("/api/v1/tickets/summary").header("Authorization", "Bearer " + agentToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pendingCount").value(0));
        String unrelatedRequester = ISSUER.issueHuman(TENANT_A, REQUESTER_B, Set.of(RoleCode.REQUESTER),
                Set.of(PermissionCode.TICKET_READ_OWN)).token();
        mockMvc.perform(get("/api/v1/tickets/summary").header("Authorization", "Bearer " + unrelatedRequester))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pendingCount").value(0));
    }

    /**
     * 验证审批摘要要求分派任务，且不授予通用工单读取权限。
     */
    @Test
    void approvalSummaryRequiresAnAssignedTaskWithoutGrantingGeneralTicketAccess() throws Exception {
        UUID ticketId = createTicket(CATALOG_HIGH, "approval-context-create");
        UUID taskId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        String token = "Bearer " + ISSUER.issueHuman(TENANT_A, actor, Set.of(RoleCode.APPROVER),
                Set.of(PermissionCode.APPROVAL_READ_ASSIGNED, PermissionCode.APPROVAL_DECIDE_ASSIGNED)).token();
        org.mockito.Mockito.when(approvalAccessChecker.canRead(TENANT_A, actor, taskId, ticketId, token)).thenReturn(true);
        mockMvc.perform(get("/api/v1/tickets/" + ticketId + "/approval-context/" + taskId).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ticketId").value(ticketId.toString()))
                .andExpect(jsonPath("$.data.subject").isNotEmpty()).andExpect(jsonPath("$.data.description").isNotEmpty())
                .andExpect(jsonPath("$.data.requesterUserId").value(REQUESTER_A.toString()));
        mockMvc.perform(get("/api/v1/tickets/" + ticketId).header("Authorization", token)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/tickets/" + ticketId + "/approval-context/" + UUID.randomUUID()).header("Authorization", token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/tickets/" + UUID.randomUUID() + "/approval-context/" + taskId).header("Authorization", token))
                .andExpect(status().isNotFound());
    }

    /**
     * 注册隔离测试数据库、密钥和功能开关，使场景在可控环境运行。
     */
    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.security.jwt.public-key-pem", () -> publicKeyPem());
        registry.add("flowdesk.security.cursor-token.hmac-key", () -> cursorHmacKey());
        registry.add("FLOWDESK_TICKET_DB_PASSWORD", () -> "");
        registry.add("spring.cloud.service-registry.auto-registration.enabled", () -> "false");
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:ticket-api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:schema-test.sql");
    }

    /**
     * 清理上次场景的持久化与替身状态，再建立本场景所需基础数据。
     */
    @BeforeEach
    void cleanAndSeed() {
        for (String table : List.of("sla_trigger", "sla_clock", "ticket_timeline_entry",
                "ticket_comment", "workflow_start_link", "outbox_event", "audit_record",
                "ticket", "ticket_number_counter", "service_catalog_item", "sla_policy")) {
            jdbc.update("DELETE FROM " + table);
        }
        insertSlaPolicy(TENANT_A, SLA_A, "DEMO_SLA", 900, 14400);
        insertSlaPolicy(TENANT_A, SLA_INACTIVE, "OLD_SLA", 600, 7200);
        jdbc.update("UPDATE sla_policy SET status = 'INACTIVE' WHERE tenant_id = ? AND sla_policy_id = ?",
                Uuids.toBytes(TENANT_A), Uuids.toBytes(SLA_INACTIVE));
        insertCatalog(TENANT_A, CATALOG_NORMAL, "LAPTOP_REQUEST", "NORMAL", false, null, null, null);
        insertCatalog(TENANT_A, CATALOG_HIGH, "PROD_DB_ACCESS", "HIGH", true,
                WORKFLOW_DEF, WORKFLOW_VERSION, 1);
        insertCatalog(TENANT_A, CATALOG_INACTIVE, "OLD_ITEM", "NORMAL", false, null, null, null);
        insertCatalog(TENANT_A, CATALOG_INACTIVE_POLICY, "INACTIVE_POLICY_ITEM", "NORMAL", false,
                null, null, null);
        jdbc.update("UPDATE service_catalog_item SET sla_policy_id = ? WHERE tenant_id = ? AND catalog_item_id = ?",
                Uuids.toBytes(SLA_INACTIVE), Uuids.toBytes(TENANT_A), Uuids.toBytes(CATALOG_INACTIVE_POLICY));
        jdbc.update("UPDATE service_catalog_item SET status = 'INACTIVE' WHERE tenant_id = ? AND catalog_item_id = ?",
                Uuids.toBytes(TENANT_A), Uuids.toBytes(CATALOG_INACTIVE));
    }

    // ----- read-only vertical slice -------------------------------------------------

    /**
     * 验证申请人只见启用目录，管理员可筛选停用项。
     */
    @Test
    void requesterSeesOnlyActiveCatalogAndAdminCanFilterInactive() throws Exception {
        mockMvc.perform(get("/api/v1/service-catalog-items")
                        .header("Authorization", "Bearer " + requesterToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[*].status").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("ACTIVE"))))
                .andExpect(jsonPath("$.meta.page.sort").value("itemName"));

        mockMvc.perform(get("/api/v1/service-catalog-items")
                        .header("Authorization", "Bearer " + requesterToken())
                        .param("status", "INACTIVE"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CATALOG_FILTER_SCOPE_FORBIDDEN"));

        mockMvc.perform(get("/api/v1/service-catalog-items")
                        .header("Authorization", "Bearer " + adminToken())
                        .param("status", "INACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("INACTIVE"));

        mockMvc.perform(get("/api/v1/service-catalog-items/" + CATALOG_INACTIVE)
                        .header("Authorization", "Bearer " + requesterToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/sla-policies")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(get("/api/v1/sla-policies")
                        .header("Authorization", "Bearer " + requesterToken()))
                .andExpect(status().isForbidden());
    }

    /**
     * 验证目录更新使用 ETag，拒绝陈旧版本。
     */
    @Test
    void adminPatchesCatalogWithEtagAndRejectsStaleVersion() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/service-catalog-items/" + CATALOG_NORMAL)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"lv:0\""))
                .andReturn();

        mockMvc.perform(patch("/api/v1/service-catalog-items/" + CATALOG_NORMAL)
                        .header("Authorization", "Bearer " + adminToken())
                        .header("If-Match", "\"lv:0\"")
                        .contentType("application/json")
                        .content("{\"itemName\":\"新名称\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.itemName").value("新名称"))
                .andExpect(jsonPath("$.data.lockVersion").value(1))
                .andExpect(header().string("ETag", "\"lv:1\""));

        mockMvc.perform(patch("/api/v1/service-catalog-items/" + CATALOG_NORMAL)
                        .header("Authorization", "Bearer " + adminToken())
                        .header("If-Match", "\"lv:1\"")
                        .contentType("application/json")
                        .content("{\"description\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lockVersion").value(2))
                .andExpect(header().string("ETag", "\"lv:2\""));
        assertThat(jdbc.queryForObject("""
                SELECT description FROM service_catalog_item
                WHERE tenant_id = ? AND catalog_item_id = ?
                """, String.class, Uuids.toBytes(TENANT_A), Uuids.toBytes(CATALOG_NORMAL)))
                .isNull();

        mockMvc.perform(patch("/api/v1/service-catalog-items/" + CATALOG_NORMAL)
                        .header("Authorization", "Bearer " + adminToken())
                        .header("If-Match", "\"lv:0\"")
                        .contentType("application/json")
                        .content("{\"itemName\":\"失败\"}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_VERSION_CONFLICT"));
    }

    // ----- create / lifecycle ---------------------------------------------------------

    /**
     * 验证人工工单从创建到分派、受理、解决和关闭的完整生命周期。
     */
    @Test
    void manualTicketLifecycleFromCreateToClose() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", "create-ticket-0001")
                        .contentType("application/json")
                        .content("""
                                {"catalogItemId":"%s","subject":"申请笔记本","description":"用于远程办公"}
                                """.formatted(CATALOG_NORMAL)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING_ASSIGNMENT"))
                .andExpect(jsonPath("$.data.ticketNo").value(
                        org.hamcrest.Matchers.matchesPattern("FD-[0-9]{8}-[0-9]{4,}")))
                .andExpect(jsonPath("$.data.originType").value("MANUAL"))
                .andExpect(jsonPath("$.data.firstProcessableAt").isNotEmpty())
                .andExpect(jsonPath("$.meta.asyncEffects").doesNotExist())
                .andExpect(header().string("ETag", "\"lv:0\""))
                .andReturn();
        String ticketId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.ticketId");

        // Same key + same intent is a replay, before If-Match checks.
        mockMvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", "create-ticket-0001")
                        .contentType("application/json")
                        .content("""
                                {"catalogItemId":"%s","subject":"申请笔记本","description":"用于远程办公"}
                                """.formatted(CATALOG_NORMAL)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.data.ticketId").value(ticketId));

        mockMvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", "create-ticket-0001")
                        .contentType("application/json")
                        .content("""
                                {"catalogItemId":"%s","subject":"不同意图","description":"用于远程办公"}
                                """.formatted(CATALOG_NORMAL)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/assign")
                        .header("Authorization", "Bearer " + adminToken())
                        .header("Idempotency-Key", "assign-ticket-0001")
                        .header("If-Match", "\"lv:0\"")
                        .contentType("application/json")
                        .content("{\"assigneeUserId\":\"%s\"}".formatted(AGENT_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.data.assigneeUserId").value(AGENT_A.toString()))
                .andExpect(jsonPath("$.data.lockVersion").value(1))
                .andExpect(header().string("ETag", "\"lv:1\""));

        // Old successful assign replay ignores the stale If-Match.
        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/assign")
                        .header("Authorization", "Bearer " + adminToken())
                        .header("Idempotency-Key", "assign-ticket-0001")
                        .header("If-Match", "\"lv:0\"")
                        .contentType("application/json")
                        .content("{\"assigneeUserId\":\"%s\"}".formatted(AGENT_A)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.data.lockVersion").value(1));

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/accept")
                        .header("Authorization", "Bearer " + agentToken())
                        .header("Idempotency-Key", "accept-ticket-0001")
                        .header("If-Match", "\"lv:1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.lockVersion").value(2));

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/comments")
                        .header("Authorization", "Bearer " + agentToken())
                        .header("Idempotency-Key", "comment-ticket-0001")
                        .contentType("application/json")
                        .content("{\"body\":\"已开始处理，等待审批。\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.authorUserId").value(AGENT_A.toString()));

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/resolve")
                        .header("Authorization", "Bearer " + agentToken())
                        .header("Idempotency-Key", "resolve-ticket-0001")
                        .header("If-Match", "\"lv:2\"")
                        .contentType("application/json")
                        .content("{\"resolutionSummary\":\"已安装软件并交付。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.resolvedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.lockVersion").value(3));

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/close")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", "close-ticket-0001")
                        .header("If-Match", "\"lv:3\"")
                        .contentType("application/json")
                        .content("{\"reason\":\"已确认结果\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.closedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.lockVersion").value(4));

        mockMvc.perform(get("/api/v1/tickets/" + ticketId + "/comments")
                        .header("Authorization", "Bearer " + requesterToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/v1/tickets/" + ticketId + "/timeline")
                        .header("Authorization", "Bearer " + requesterToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(8))
                .andExpect(jsonPath("$.data[*].eventType", org.hamcrest.Matchers.hasItem(
                        "SLA_SATISFIED_ON_TIME")));

        mockMvc.perform(get("/api/v1/tickets/" + ticketId + "/timeline")
                        .header("Authorization", "Bearer " + requesterToken())
                        .param("eventType", "SLA_SATISFIED_ON_TIME"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(get("/api/v1/tickets/" + ticketId + "/sla-status")
                        .header("Authorization", "Bearer " + requesterToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clocks.length()").value(2))
                .andExpect(jsonPath("$.data.clocks[?(@.clockType=='FIRST_RESPONSE')].status")
                        .value(org.hamcrest.Matchers.contains("SATISFIED_ON_TIME")))
                .andExpect(jsonPath("$.data.clocks[?(@.clockType=='RESOLUTION')].status")
                        .value(org.hamcrest.Matchers.contains("SATISFIED_ON_TIME")));

        // A new comment on the closed ticket is rejected...
        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/comments")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", "comment-ticket-0002")
                        .contentType("application/json")
                        .content("{\"body\":\"迟到评论\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TICKET_TERMINAL"));

        // ...but replaying the already-successful comment by the same actor must be
        // recognized before the terminal-state precondition (API-SPEC §4.2 order).
        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/comments")
                        .header("Authorization", "Bearer " + agentToken())
                        .header("Idempotency-Key", "comment-ticket-0001")
                        .contentType("application/json")
                        .content("{\"body\":\"已开始处理，等待审批。\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.data.commentId").isNotEmpty());
    }

    /**
     * 验证并发建单分配一百个唯一且格式正确的工单号。
     */
    @Test
    void concurrentTicketCreationAllocatesOneHundredUniqueFormattedNumbers() throws Exception {
        int requestCount = 100;
        String token = requesterToken();
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> results = new java.util.ArrayList<>(requestCount);
            for (int index = 0; index < requestCount; index++) {
                int worker = index;
                results.add(executor.submit(() -> {
                    start.await();
                    MvcResult result = mockMvc.perform(post("/api/v1/tickets")
                                    .header("Authorization", "Bearer " + token)
                                    .header("Idempotency-Key", "concurrent-ticket-number-%04d".formatted(worker))
                                    .contentType("application/json")
                                    .content("""
                                            {"catalogItemId":"%s","subject":"并发编号 %d","description":"原子编号验证"}
                                            """.formatted(CATALOG_NORMAL, worker)))
                            .andExpect(status().isCreated())
                            .andReturn();
                    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.ticketNo");
                }));
            }

            start.countDown();
            Set<String> ticketNumbers = new HashSet<>();
            for (Future<String> result : results) {
                String ticketNo = result.get(30, TimeUnit.SECONDS);
                assertThat(ticketNo).matches("FD-[0-9]{8}-[0-9]{4,}");
                ticketNumbers.add(ticketNo);
            }

            assertThat(ticketNumbers).hasSize(requestCount);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Integer.class))
                    .isEqualTo(requestCount);
            assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT ticket_no) FROM ticket", Integer.class))
                    .isEqualTo(requestCount);
            assertThat(jdbc.queryForObject("SELECT MAX(sequence_value) FROM ticket_number_counter", Long.class))
                    .isEqualTo((long) requestCount);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 验证相同幂等键并发请求最终只产生一次完整业务效果。
     */
    @RepeatedTest(10)
    void concurrentSameIdempotencyKeyProducesOneCompletedBusinessEffect() throws Exception {
        int requestCount = 20;
        String token = requesterToken();
        String requestBody = """
                {"catalogItemId":"%s","subject":"并发幂等","description":"同一请求只产生一个效果"}
                """.formatted(CATALOG_NORMAL);
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> results = new java.util.ArrayList<>(requestCount);
            for (int index = 0; index < requestCount; index++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return mockMvc.perform(post("/api/v1/tickets")
                                    .header("Authorization", "Bearer " + token)
                                    .header("Idempotency-Key", "concurrent-same-create-0001")
                                    .contentType("application/json")
                                    .content(requestBody))
                            .andReturn();
                }));
            }

            start.countDown();
            Set<String> ticketIds = new HashSet<>();
            int createdResponses = 0;
            int replayResponses = 0;
            for (Future<MvcResult> future : results) {
                MvcResult result = future.get(30, TimeUnit.SECONDS);
                int responseStatus = result.getResponse().getStatus();
                assertThat(responseStatus)
                        .withFailMessage("并发幂等响应未收敛：status=%s, body=%s",
                                responseStatus, result.getResponse().getContentAsString())
                        .isIn(200, 201);
                ticketIds.add(JsonPath.read(result.getResponse().getContentAsString(),
                        "$.data.ticketId"));
                if (responseStatus == 201) {
                    createdResponses++;
                    assertThat(result.getResponse().getHeader("Idempotency-Replayed")).isNull();
                } else {
                    replayResponses++;
                    assertThat(result.getResponse().getHeader("Idempotency-Replayed"))
                            .isEqualTo("true");
                }
            }

            assertThat(ticketIds).hasSize(1);
            assertThat(createdResponses).isEqualTo(1);
            assertThat(replayResponses).isEqualTo(requestCount - 1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM ticket_timeline_entry WHERE event_type = 'CREATED'
                    """, Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM audit_record WHERE action_code = 'TICKET_CREATE'
                    """, Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sla_clock", Integer.class))
                    .isEqualTo(2);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 验证相同文本幂等键按调用用户隔离。
     */
    @Test
    void sameTextualIdempotencyKeyIsScopedToTheCallingUser() throws Exception {
        String key = "same-key-different-requesters-0001";
        String requestBody = """
                {"catalogItemId":"%s","subject":"用户级幂等键","description":"调用方必须隔离"}
                """.formatted(CATALOG_NORMAL);

        MvcResult requesterAResult = mockMvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult requesterBResult = mockMvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + requesterBToken())
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        String requesterATicketId = JsonPath.read(
                requesterAResult.getResponse().getContentAsString(), "$.data.ticketId");
        String requesterBTicketId = JsonPath.read(
                requesterBResult.getResponse().getContentAsString(), "$.data.ticketId");
        assertThat(requesterATicketId).isNotEqualTo(requesterBTicketId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT requester_user_id) FROM ticket", Integer.class))
                .isEqualTo(2);
    }
    /**
     * 验证高风险建单进入待审批，并写入启动发件箱。
     */
    @Test
    void highRiskTicketEntersAwaitingApprovalWithOutbox() throws Exception {
        MvcResult initialCreated = mockMvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", "create-high-risk-01")
                        .contentType("application/json")
                        .content("""
                                {"catalogItemId":"%s","subject":"申请生产权限","description":"故障排查只读权限"}
                                """.formatted(CATALOG_HIGH)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.data.firstProcessableAt").doesNotExist())
                .andExpect(jsonPath("$.data.workflowRef.definitionId").value(WORKFLOW_DEF.toString()))
                .andExpect(jsonPath("$.meta.asyncEffects[0].kind").value("WORKFLOW_START"))
                .andExpect(jsonPath("$.meta.asyncEffects[0].state").value("REQUESTED"))
                .andReturn();
        UUID initialTicketId = UUID.fromString(JsonPath.read(
                initialCreated.getResponse().getContentAsString(), "$.data.ticketId"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_start_link", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT aggregate_type FROM outbox_event", String.class))
                .isEqualTo(TicketEventContract.WorkflowStartRequested.AGGREGATE_TYPE);
        assertThat(jdbc.queryForObject("SELECT contract_ref FROM outbox_event", String.class))
                .isEqualTo(TicketEventContract.WorkflowStartRequested.CONTRACT_REF);
        assertThat(jdbc.queryForObject("SELECT contract_version FROM outbox_event", Integer.class))
                .isEqualTo(TicketEventContract.WorkflowStartRequested.EVENT_VERSION);
        byte[] eventBodyBytes = jdbc.queryForObject("SELECT body_bytes FROM outbox_event", byte[].class);
        assertThat(eventBodyBytes).isNotNull();
        assertThat(eventBodyBytes).containsExactly(WorkflowStartV1PayloadCodec.encode(
                initialTicketId, WORKFLOW_DEF, WORKFLOW_VERSION, 1, REQUESTER_A));
        assertThat(jdbc.queryForObject("SELECT body_sha256 FROM outbox_event", byte[].class))
                .containsExactly(MessageDigest.getInstance("SHA-256").digest(eventBodyBytes));
        String eventBody = new String(eventBodyBytes, StandardCharsets.UTF_8);
        assertThat(JsonPath.<String>read(eventBody, "$.schemaVersion"))
                .isEqualTo(TicketEventContract.WorkflowStartRequested.PAYLOAD_SCHEMA_VERSION);
        assertThat(JsonPath.<String>read(eventBody, "$.serviceRiskLevel")).isEqualTo("HIGH");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sla_clock", Integer.class)).isZero();

        MvcResult created = mockMvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", "create-high-risk-01")
                        .contentType("application/json")
                        .content("""
                                {"catalogItemId":"%s","subject":"申请生产权限","description":"故障排查只读权限"}
                                """.formatted(CATALOG_HIGH)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn();
        String ticketId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.ticketId");
        assertThat(ticketId).isEqualTo(initialTicketId.toString());

        mockMvc.perform(get("/api/v1/tickets/" + ticketId + "/workflow-start")
                        .header("Authorization", "Bearer " + requesterToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticketId").value(ticketId))
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andExpect(header().string("ETag", "\"lv:0\""));
    }

    /**
     * 验证启用目录绑定停用策略时仍按约定保存策略快照。
     */
    @Test
    void activeCatalogBoundToInactiveSlaPolicyStillSnapshotsPolicy() throws Exception {
        mockMvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", "create-inactive-sla-01")
                        .contentType("application/json")
                        .content("""
                                {"catalogItemId":"%s","subject":"旧策略目录建单","description":"策略可快照"}
                                """.formatted(CATALOG_INACTIVE_POLICY)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING_ASSIGNMENT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sla_clock WHERE sla_policy_id = ?",
                Integer.class, Uuids.toBytes(SLA_INACTIVE))).isEqualTo(2);
    }

    /**
     * 验证前次申请只能引用本人被拒绝的高风险工单。
     */
    @Test
    void previousAttemptMustBeOwnRejectedHighRiskTicket() throws Exception {
        UUID rejected = createTicket(CATALOG_HIGH, "create-high-risk-02");
        jdbc.update("""
                UPDATE ticket SET status = 'APPROVAL_REJECTED', terminal_at = CURRENT_TIMESTAMP(6)
                WHERE tenant_id = ? AND ticket_id = ?
                """, Uuids.toBytes(TENANT_A), Uuids.toBytes(rejected));

        UUID normal = createTicket(CATALOG_NORMAL, "create-normal-0002");
        mockMvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", "create-retry-0001")
                        .contentType("application/json")
                        .content("""
                                {"catalogItemId":"%s","subject":"重试申请","description":"重试","previousAttemptTicketId":"%s"}
                                """.formatted(CATALOG_HIGH, normal)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("PREVIOUS_ATTEMPT_INVALID"));

        mockMvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", "create-retry-0002")
                        .contentType("application/json")
                        .content("""
                                {"catalogItemId":"%s","subject":"重试申请","description":"重试","previousAttemptTicketId":"%s"}
                                """.formatted(CATALOG_HIGH, rejected)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.previousAttemptTicketId").value(rejected.toString()))
                .andExpect(jsonPath("$.data.status").value("AWAITING_APPROVAL"));
    }

    // ----- authorization and conflicts ---------------------------------------------------

    /**
     * 验证权限范围与跨租户隔离，阻止读取或操作他方数据。
     */
    @Test
    void authorizationScopeAndCrossTenantIsolation() throws Exception {
        UUID ticketId = createTicket(CATALOG_NORMAL, "create-scope-0001");

        mockMvc.perform(get("/api/v1/tickets")
                        .header("Authorization", "Bearer " + requesterToken())
                        .param("scope", "tenant"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("TICKET_SCOPE_FORBIDDEN"));

        mockMvc.perform(get("/api/v1/tickets/" + ticketId)
                        .header("Authorization", "Bearer " + requesterBToken()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/assign")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", "assign-forbidden-01")
                        .header("If-Match", "\"lv:0\"")
                        .contentType("application/json")
                        .content("{\"assigneeUserId\":\"%s\"}".formatted(AGENT_A)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));

        // Assign then a different agent cannot accept.
        assignTicket(ticketId, AGENT_A, "assign-scope-0001");
        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/accept")
                        .header("Authorization", "Bearer " + agent2Token())
                        .header("Idempotency-Key", "accept-forbidden-01")
                        .header("If-Match", "\"lv:1\""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("TICKET_NOT_ASSIGNED_TO_ACTOR"));
    }

    /**
     * 验证陈旧版本和非法状态被拒绝，且不产生副作用。
     */
    @Test
    void staleEtagAndIllegalStateAreRejectedWithoutSideEffects() throws Exception {
        UUID ticketId = createTicket(CATALOG_NORMAL, "create-conflict-01");

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/assign")
                        .header("Authorization", "Bearer " + adminToken())
                        .header("Idempotency-Key", "assign-conflict-01")
                        .header("If-Match", "\"lv:7\"")
                        .contentType("application/json")
                        .content("{\"assigneeUserId\":\"%s\"}".formatted(AGENT_A)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_VERSION_CONFLICT"));

        // Assign first, then the current agent resolves from ASSIGNED: state guard fires
        // before any write.
        assignTicket(ticketId, AGENT_A, "assign-conflict-02");
        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/resolve")
                        .header("Authorization", "Bearer " + agentToken())
                        .header("Idempotency-Key", "resolve-conflict-01")
                        .header("If-Match", "\"lv:1\"")
                        .contentType("application/json")
                        .content("{\"resolutionSummary\":\"非法状态尝试\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TICKET_STATE_INVALID"));

        // ASSIGNED + CREATED only: the rejected transitions produced no timeline effects.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_timeline_entry WHERE ticket_id = ?",
                Integer.class, Uuids.toBytes(ticketId))).isEqualTo(2);
    }

    /**
     * 验证受理失败不写成功幂等事实，分派后仍可成功。
     */
    @Test
    void failedAcceptDoesNotCreateReplayAndCanSucceedAfterAssignment() throws Exception {
        UUID ticketId = createTicket(CATALOG_NORMAL, "create-accept-after-failure-0001");
        String acceptKey = "accept-after-failure-0001";

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/accept")
                        .header("Authorization", "Bearer " + agentToken())
                        .header("Idempotency-Key", acceptKey)
                        .header("If-Match", "\"lv:0\""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("TICKET_NOT_ASSIGNED_TO_ACTOR"));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ticket_timeline_entry
                WHERE ticket_id = ? AND event_type = 'ACCEPTED'
                """, Integer.class, Uuids.toBytes(ticketId))).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_record
                WHERE target_id = ? AND action_code = 'TICKET_ACCEPT'
                """, Integer.class, Uuids.toBytes(ticketId))).isZero();

        assignTicket(ticketId, AGENT_A, "assign-after-accept-failure-0001");
        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/accept")
                        .header("Authorization", "Bearer " + agentToken())
                        .header("Idempotency-Key", acceptKey)
                        .header("If-Match", "\"lv:1\""))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.lockVersion").value(2));

        String sameActorWithoutAcceptPermission = token(AGENT_A, RoleCode.AGENT,
                PermissionCode.TICKET_READ_ASSIGNED);
        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/accept")
                        .header("Authorization", "Bearer " + sameActorWithoutAcceptPermission)
                        .header("Idempotency-Key", acceptKey)
                        .header("If-Match", "\"lv:1\""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/accept")
                        .header("Authorization", "Bearer " + agentToken())
                        .header("Idempotency-Key", acceptKey)
                        .header("If-Match", "\"lv:1\""))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.lockVersion").value(2));

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/accept")
                        .header("Authorization", "Bearer " + agentToken())
                        .header("Idempotency-Key", "accept-new-key-invalid-state-0001")
                        .header("If-Match", "\"lv:2\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TICKET_STATE_INVALID"));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ticket_timeline_entry
                WHERE ticket_id = ? AND event_type = 'ACCEPTED'
                """, Integer.class, Uuids.toBytes(ticketId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_record
                WHERE target_id = ? AND action_code = 'TICKET_ACCEPT'
                  AND outcome = 'SUCCEEDED'
                """, Integer.class, Uuids.toBytes(ticketId))).isEqualTo(1);
    }
    /**
     * 验证无处理资格的用户不能被分派工单。
     */
    @Test
    void ineligibleAgentIsRejected() throws Exception {
        UUID ticketId = createTicket(CATALOG_NORMAL, "create-ineligible-01");
        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/assign")
                        .header("Authorization", "Bearer " + adminToken())
                        .header("Idempotency-Key", "assign-ineligible-01")
                        .header("If-Match", "\"lv:0\"")
                        .contentType("application/json")
                        .content("{\"assigneeUserId\":\"%s\"}".formatted(INELIGIBLE)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ASSIGNEE_NOT_ELIGIBLE"));
    }

    /**
     * 验证转派回到已分派状态，重开保留处理人。
     */
    @Test
    void transferMovesToAssignedAndReopenKeepsAssignee() throws Exception {
        UUID ticketId = createTicket(CATALOG_NORMAL, "create-transfer-01");
        assignTicket(ticketId, AGENT_A, "assign-transfer-01");

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/transfer")
                        .header("Authorization", "Bearer " + agentToken())
                        .header("Idempotency-Key", "transfer-ticket-01")
                        .header("If-Match", "\"lv:1\"")
                        .contentType("application/json")
                        .content("{\"newAssigneeUserId\":\"%s\",\"reason\":\"换人处理\"}"
                                .formatted(AGENT_A2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.data.assigneeUserId").value(AGENT_A2.toString()))
                .andExpect(jsonPath("$.data.lockVersion").value(2));

        // Replay is bound to the original actor's effect key and must be decided
        // before checking who is assigned after the first transfer.
        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/transfer")
                        .header("Authorization", "Bearer " + agentToken())
                        .header("Idempotency-Key", "transfer-ticket-01")
                        .header("If-Match", "\"lv:1\"")
                        .contentType("application/json")
                        .content("{\"newAssigneeUserId\":\"%s\",\"reason\":\"换人处理\"}"
                                .formatted(AGENT_A2)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.data.assigneeUserId").value(AGENT_A2.toString()))
                .andExpect(jsonPath("$.data.lockVersion").value(2));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ticket_timeline_entry
                WHERE ticket_id = ? AND event_type = 'TRANSFERRED'
                """, Integer.class, Uuids.toBytes(ticketId))).isEqualTo(1);

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/accept")
                        .header("Authorization", "Bearer " + agent2Token())
                        .header("Idempotency-Key", "accept-transfer-01")
                        .header("If-Match", "\"lv:2\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/resolve")
                        .header("Authorization", "Bearer " + agent2Token())
                        .header("Idempotency-Key", "resolve-transfer-01")
                        .header("If-Match", "\"lv:3\"")
                        .contentType("application/json")
                        .content("{\"resolutionSummary\":\"已解决\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/reopen")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", "reopen-ticket-01")
                        .header("If-Match", "\"lv:4\"")
                        .contentType("application/json")
                        .content("{\"reason\":\"问题仍在\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.assigneeUserId").value(AGENT_A2.toString()))
                .andExpect(jsonPath("$.data.reopenCount").value(1));

        // Reopen keeps the original resolvedAt.
        mockMvc.perform(get("/api/v1/tickets/" + ticketId)
                        .header("Authorization", "Bearer " + requesterToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolvedAt").isNotEmpty());
        mockMvc.perform(get("/api/v1/tickets/" + ticketId + "/sla-status").header("Authorization", "Bearer " + requesterToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.clocks.length()").value(1))
                .andExpect(jsonPath("$.data.clocks[0].cycleNo").value(1))
                .andExpect(jsonPath("$.data.clocks[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.data.historyClocks.length()").value(2));
        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/reopen")
                        .header("Authorization", "Bearer " + requesterToken()).header("Idempotency-Key", "reopen-ticket-01")
                        .header("If-Match", "\"lv:4\"").contentType("application/json").content("{\"reason\":\"问题仍在\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sla_clock WHERE ticket_id=?", Integer.class, Uuids.toBytes(ticketId))).isEqualTo(3);
        assertThat(jdbc.query("SELECT body_bytes FROM outbox_event WHERE aggregate_type='NOTIFICATION_REQUEST'",
                (rs,n) -> new String(rs.getBytes(1), StandardCharsets.UTF_8)).stream().filter(body -> body.contains("TICKET_REOPENED")).count()).isEqualTo(1);
        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/resolve")
                        .header("Authorization", "Bearer " + agent2Token()).header("Idempotency-Key", "resolve-reopened-01")
                        .header("If-Match", "\"lv:5\"").contentType("application/json").content("{\"resolutionSummary\":\"重新处理完成\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM sla_clock WHERE ticket_id=? AND cycle_no=1", String.class, Uuids.toBytes(ticketId)))
                .isEqualTo("SATISFIED_ON_TIME");
    }

    /**
     * 验证后续状态和处理人变化不影响既有成功重放。
     */
    @Test
    void successfulReplaysSurviveLaterStateAndAssigneeChanges() throws Exception {
        UUID ticketId = createTicket(CATALOG_NORMAL, "create-replay-chain-0001");
        assignTicket(ticketId, AGENT_A, "assign-replay-chain-0001");
        acceptTicket(ticketId, agentToken(), "accept-shared-actor-0001", 1);

        // The same key text is actor-scoped and cannot expose another actor's effect.
        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/accept")
                        .header("Authorization", "Bearer " + agent2Token())
                        .header("Idempotency-Key", "accept-shared-actor-0001")
                        .header("If-Match", "\"lv:1\""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("TICKET_NOT_ASSIGNED_TO_ACTOR"));

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/transfer")
                        .header("Authorization", "Bearer " + agentToken())
                        .header("Idempotency-Key", "transfer-after-accept-0001")
                        .header("If-Match", "\"lv:2\"")
                        .contentType("application/json")
                        .content("{\"newAssigneeUserId\":\"%s\",\"reason\":\"转派后验证重放\"}"
                                .formatted(AGENT_A2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.data.assigneeUserId").value(AGENT_A2.toString()))
                .andExpect(jsonPath("$.data.lockVersion").value(3));

        // Replay is decided before mutable assignee, state, and If-Match checks.
        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/accept")
                        .header("Authorization", "Bearer " + agentToken())
                        .header("Idempotency-Key", "accept-shared-actor-0001")
                        .header("If-Match", "\"lv:1\""))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.data.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.data.assigneeUserId").value(AGENT_A2.toString()))
                .andExpect(jsonPath("$.data.lockVersion").value(3));

        // A different actor may legitimately use the same key text for a new effect.
        acceptTicket(ticketId, agent2Token(), "accept-shared-actor-0001", 3);
        resolveTicket(ticketId, agent2Token(), "resolve-before-close-0001", 4,
                "第一次解决");

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/close")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", "close-after-resolve-0001")
                        .header("If-Match", "\"lv:5\"")
                        .contentType("application/json")
                        .content("{\"reason\":\"确认关闭\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.lockVersion").value(6));

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/resolve")
                        .header("Authorization", "Bearer " + agent2Token())
                        .header("Idempotency-Key", "resolve-before-close-0001")
                        .header("If-Match", "\"lv:4\"")
                        .contentType("application/json")
                        .content("{\"resolutionSummary\":\"第一次解决\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.lockVersion").value(6));

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/resolve")
                        .header("Authorization", "Bearer " + agent2Token())
                        .header("Idempotency-Key", "resolve-before-close-0001")
                        .header("If-Match", "\"lv:4\"")
                        .contentType("application/json")
                        .content("{\"resolutionSummary\":\"同 key 的不同摘要\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/close")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", "close-after-resolve-0001")
                        .header("If-Match", "\"lv:5\"")
                        .contentType("application/json")
                        .content("{\"reason\":\"确认关闭\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.lockVersion").value(6));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ticket_timeline_entry
                WHERE ticket_id = ? AND event_type = 'ACCEPTED'
                """, Integer.class, Uuids.toBytes(ticketId))).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ticket_timeline_entry
                WHERE ticket_id = ? AND event_type = 'RESOLVED'
                """, Integer.class, Uuids.toBytes(ticketId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ticket_timeline_entry
                WHERE ticket_id = ? AND event_type = 'CLOSED'
                """, Integer.class, Uuids.toBytes(ticketId))).isEqualTo(1);
    }

    /**
     * 验证再次解决后重开仍可重放，且不重复增加周期次数。
     */
    @Test
    void reopenReplaySurvivesSecondResolveWithoutIncrementingAgain() throws Exception {
        UUID ticketId = createTicket(CATALOG_NORMAL, "create-reopen-replay-0001");
        assignTicket(ticketId, AGENT_A, "assign-reopen-replay-0001");
        acceptTicket(ticketId, agentToken(), "accept-reopen-replay-0001", 1);
        resolveTicket(ticketId, agentToken(), "resolve-reopen-first-0001", 2,
                "首次解决");

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/reopen")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", "reopen-replay-key-0001")
                        .header("If-Match", "\"lv:3\"")
                        .contentType("application/json")
                        .content("{\"reason\":\"问题仍然存在\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.reopenCount").value(1))
                .andExpect(jsonPath("$.data.lockVersion").value(4));

        resolveTicket(ticketId, agentToken(), "resolve-reopen-second-0001", 4,
                "再次解决");

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/reopen")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", "reopen-replay-key-0001")
                        .header("If-Match", "\"lv:3\"")
                        .contentType("application/json")
                        .content("{\"reason\":\"问题仍然存在\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.reopenCount").value(1))
                .andExpect(jsonPath("$.data.lockVersion").value(5));

        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/reopen")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", "reopen-replay-key-0001")
                        .header("If-Match", "\"lv:3\"")
                        .contentType("application/json")
                        .content("{\"reason\":\"同 key 的不同原因\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ticket_timeline_entry
                WHERE ticket_id = ? AND event_type = 'REOPENED'
                """, Integer.class, Uuids.toBytes(ticketId))).isEqualTo(1);
    }

    /**
     * 验证缺失时间线或审计锚点时拒绝成功重放。
     */
    @Test
    void incompleteTimelineOrAuditAnchorFailsClosed() throws Exception {
        UUID missingAuditTicket = createTicket(CATALOG_NORMAL, "create-missing-audit-0001");
        assignTicket(missingAuditTicket, AGENT_A, "assign-missing-audit-0001");
        acceptTicket(missingAuditTicket, agentToken(), "accept-missing-audit-0001", 1);
        jdbc.update("""
                DELETE FROM audit_record
                WHERE target_id = ? AND action_code = 'TICKET_ACCEPT'
                """, Uuids.toBytes(missingAuditTicket));

        mockMvc.perform(post("/api/v1/tickets/" + missingAuditTicket + "/accept")
                        .header("Authorization", "Bearer " + agentToken())
                        .header("Idempotency-Key", "accept-missing-audit-0001")
                        .header("If-Match", "\"lv:1\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

        UUID missingTimelineTicket = createTicket(CATALOG_NORMAL, "create-missing-timeline-0001");
        assignTicket(missingTimelineTicket, AGENT_A, "assign-missing-timeline-0001");
        acceptTicket(missingTimelineTicket, agentToken(), "accept-missing-timeline-0001", 1);
        jdbc.update("""
                DELETE FROM ticket_timeline_entry
                WHERE ticket_id = ? AND event_type = 'ACCEPTED'
                """, Uuids.toBytes(missingTimelineTicket));

        mockMvc.perform(post("/api/v1/tickets/" + missingTimelineTicket + "/accept")
                        .header("Authorization", "Bearer " + agentToken())
                        .header("Idempotency-Key", "accept-missing-timeline-0001")
                        .header("If-Match", "\"lv:1\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    /**
     * 验证一致性重读后创建锚点仍缺失时继续拒绝重放。
     */
    @Test
    void incompleteCreateAnchorStillFailsClosedAfterConsistencyRefresh() throws Exception {
        String key = "create-incomplete-anchor-0001";
        UUID ticketId = createTicket(CATALOG_NORMAL, key);
        jdbc.update("""
                DELETE FROM audit_record
                WHERE tenant_id = ? AND target_id = ? AND action_code = 'TICKET_CREATE'
                """, Uuids.toBytes(TENANT_A), Uuids.toBytes(ticketId));

        mockMvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("""
                                {"catalogItemId":"%s","subject":"测试工单","description":"测试描述"}
                                """.formatted(CATALOG_NORMAL)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ticket_timeline_entry WHERE event_type = 'CREATED'
                """, Integer.class)).isEqualTo(1);
    }

    /**
     * 验证工单号冲突不被误识别为幂等重放。
     */
    @Test
    void ticketNumberConflictCannotMasqueradeAsReplay() throws Exception {
        UUID ticketId = createTicket(CATALOG_NORMAL, "create-number-anchor-0001");
        String originalTicketNo = jdbc.queryForObject("""
                SELECT ticket_no FROM ticket WHERE tenant_id = ? AND ticket_id = ?
                """, String.class, Uuids.toBytes(TENANT_A), Uuids.toBytes(ticketId));
        String collidingTicketNo = originalTicketNo.substring(
                0, originalTicketNo.lastIndexOf('-') + 1) + "0002";
        jdbc.update("UPDATE ticket SET ticket_no = ? WHERE tenant_id = ? AND ticket_id = ?",
                collidingTicketNo, Uuids.toBytes(TENANT_A), Uuids.toBytes(ticketId));

        mockMvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", "create-number-conflict-0002")
                        .contentType("application/json")
                        .content("""
                                {"catalogItemId":"%s","subject":"编号冲突","description":"不得重放"}
                                """.formatted(CATALOG_NORMAL)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TICKET_NUMBER_CONFLICT"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT sequence_value FROM ticket_number_counter WHERE tenant_id = ?",
                Long.class, Uuids.toBytes(TENANT_A))).isEqualTo(1L);
    }

    /**
     * 验证无关唯一约束冲突不冒充重放，回滚后可重试。
     */
    @Test
    void unrelatedUniqueConstraintCannotMasqueradeAsReplayAndRollbackCanRetry() throws Exception {
        String retryKey = "create-unrelated-conflict-0001";
        String requestBody = """
                {"catalogItemId":"%s","subject":"其他约束","description":"不得重放"}
                """.formatted(CATALOG_NORMAL);
        createTicket(CATALOG_NORMAL, "create-unrelated-anchor-0001");
        jdbc.execute("""
                ALTER TABLE audit_record ADD CONSTRAINT uk_test_unrelated_audit_action
                UNIQUE (action_code)
                """);
        try {
            mockMvc.perform(post("/api/v1/tickets")
                            .header("Authorization", "Bearer " + requesterToken())
                            .header("Idempotency-Key", retryKey)
                            .contentType("application/json")
                            .content(requestBody))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_ALREADY_EXISTS"));

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT sequence_value FROM ticket_number_counter WHERE tenant_id = ?",
                    Long.class, Uuids.toBytes(TENANT_A))).isEqualTo(1L);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM audit_record
                    WHERE action_code = 'TICKET_CREATE' AND reason IS NOT NULL
                    """, Integer.class)).isEqualTo(1);
        } finally {
            jdbc.execute("""
                    ALTER TABLE audit_record DROP CONSTRAINT uk_test_unrelated_audit_action
                    """);
        }

        mockMvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", retryKey)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotency-Replayed"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT sequence_value FROM ticket_number_counter WHERE tenant_id = ?",
                Long.class, Uuids.toBytes(TENANT_A))).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_record WHERE action_code = 'TICKET_CREATE'
                """, Integer.class)).isEqualTo(2);
    }
    /**
     * 验证双向分页保留同时间记录与用户可见范围。
     */
    @Test
    void filteredTicketPagesKeepTimestampTiesAndVisibilityScopesInBothDirections() throws Exception {
        Instant sameTime = Instant.parse("2026-10-20T10:00:00.123456Z");
        List<UUID> expected = java.util.stream.IntStream.rangeClosed(1, 4)
                .mapToObj(i -> UUID.fromString("71000000-0000-0000-0000-%012d".formatted(i)))
                .toList();
        for (UUID id : expected) {
            insertQueryTicket(TENANT_A, id, "ASSIGNED", "MANUAL", CATALOG_NORMAL,
                    REQUESTER_A, AGENT_A, sameTime);
        }
        // Each decoy differs from one filter, so dropping any predicate changes a page.
        insertQueryTicket(TENANT_A, UUID.randomUUID(), "RESOLVED", "MANUAL", CATALOG_NORMAL,
                REQUESTER_A, AGENT_A, sameTime);
        insertQueryTicket(TENANT_A, UUID.randomUUID(), "ASSIGNED", "INCIDENT", CATALOG_NORMAL,
                REQUESTER_A, AGENT_A, sameTime);
        insertQueryTicket(TENANT_A, UUID.randomUUID(), "ASSIGNED", "MANUAL", CATALOG_HIGH,
                REQUESTER_A, AGENT_A, sameTime);
        insertQueryTicket(TENANT_A, UUID.randomUUID(), "ASSIGNED", "MANUAL", CATALOG_NORMAL,
                REQUESTER_A, AGENT_A, sameTime.minusSeconds(1));
        insertQueryTicket(TENANT_A, UUID.randomUUID(), "ASSIGNED", "MANUAL", CATALOG_NORMAL,
                REQUESTER_A, AGENT_A, sameTime.plusSeconds(1));
        UUID otherVisible = UUID.fromString("71000000-0000-0000-0000-000000000005");
        insertQueryTicket(TENANT_A, otherVisible, "ASSIGNED", "MANUAL", CATALOG_NORMAL,
                REQUESTER_B, AGENT_A2, sameTime);
        UUID assignedOnly = UUID.fromString("71000000-0000-0000-0000-000000000006");
        insertQueryTicket(TENANT_A, assignedOnly, "ASSIGNED", "MANUAL", CATALOG_NORMAL,
                REQUESTER_B, REQUESTER_A, sameTime);
        insertQueryTicket(TENANT_B, UUID.randomUUID(), "ASSIGNED", "MANUAL", CATALOG_NORMAL,
                REQUESTER_A, AGENT_A, sameTime);
        var filters = new org.springframework.util.LinkedMultiValueMap<String, String>();
        filters.add("status", "ASSIGNED");
        filters.add("originType", "MANUAL");
        filters.add("catalogItemId", CATALOG_NORMAL.toString());
        // Equal endpoints exercise both inclusive timestamp predicates.
        filters.add("createdFrom", sameTime.toString());
        filters.add("createdTo", sameTime.toString());
        String mixed = ISSUER.issueHuman(TENANT_A, REQUESTER_A, Set.of(RoleCode.REQUESTER, RoleCode.AGENT),
                Set.of(PermissionCode.TICKET_READ_OWN, PermissionCode.TICKET_READ_ASSIGNED)).token();
        for (String sort : List.of("createdAt", "-createdAt", "updatedAt", "-updatedAt")) {
            List<UUID> order = new java.util.ArrayList<>(expected);
            if (sort.startsWith("-")) java.util.Collections.reverse(order);
            filters.set("scope", "mine");
            assertThat(readQueryPages("/api/v1/tickets", requesterToken(), filters, sort, "ticketId"))
                    .containsExactlyElementsOf(order.stream().map(UUID::toString).toList());
            filters.set("scope", "assigned");
            assertThat(readQueryPages("/api/v1/tickets", agentToken(), filters, sort, "ticketId"))
                    .containsExactlyElementsOf(order.stream().map(UUID::toString).toList());
            filters.set("scope", "visible");
            List<UUID> visibleOrder = new java.util.ArrayList<>(expected);
            visibleOrder.add(assignedOnly);
            if (sort.startsWith("-")) java.util.Collections.reverse(visibleOrder);
            assertThat(readQueryPages("/api/v1/tickets", mixed, filters, sort, "ticketId"))
                    .containsExactlyElementsOf(visibleOrder.stream().map(UUID::toString).toList());
        }
        filters.set("scope", "tenant");
        List<UUID> tenantRows = new java.util.ArrayList<>(expected);
        tenantRows.add(otherVisible);
        tenantRows.add(assignedOnly);
        assertThat(readQueryPages("/api/v1/tickets", adminToken(), filters, "createdAt", "ticketId"))
                .containsExactlyElementsOf(tenantRows.stream().map(UUID::toString).toList());
        // Switching from timestamps to ticket numbers exercises the String cursor type.
        filters.set("scope", "mine");
        assertThat(readQueryPages("/api/v1/tickets", requesterToken(), filters, "ticketNo", "ticketId"))
                .containsExactlyElementsOf(expected.stream().map(UUID::toString).toList());
    }

    /**
     * 验证评论按时间与 UUID 分页，不越过工单或租户边界。
     */
    @Test
    void commentPagesUseTimestampAndUuidWithoutCrossingTicketOrTenantBoundaries() throws Exception {
        UUID ticketId = createTicket(CATALOG_NORMAL, "comment-query-pagination-ticket");
        Instant sameTime = Instant.parse("2026-10-20T11:00:00.123456Z");
        List<UUID> ids = java.util.stream.IntStream.rangeClosed(1, 4)
                .mapToObj(i -> UUID.fromString("72000000-0000-0000-0000-%012d".formatted(i)))
                .toList();
        for (UUID id : ids) insertQueryComment(TENANT_A, ticketId, id, sameTime);
        insertQueryComment(TENANT_B, ticketId, UUID.randomUUID(), sameTime);
        insertQueryComment(TENANT_A, UUID.randomUUID(), UUID.randomUUID(), sameTime);
        for (String sort : List.of("createdAt", "-createdAt")) {
            List<UUID> order = new java.util.ArrayList<>(ids);
            if (sort.startsWith("-")) java.util.Collections.reverse(order);
            assertThat(readQueryPages("/api/v1/tickets/" + ticketId + "/comments", requesterToken(),
                    new org.springframework.util.LinkedMultiValueMap<>(), sort, "commentId"))
                    .containsExactlyElementsOf(order.stream().map(UUID::toString).toList());
        }
    }

    /**
     * 验证时间线双向分页保持时间筛选和同时间 UUID 边界。
     */
    @Test
    void timelinePagesKeepEventTimeFiltersAndUuidTiesInBothDirections() throws Exception {
        UUID ticketId = createTicket(CATALOG_NORMAL, "timeline-query-pagination-ticket");
        Instant sameTime = Instant.parse("2026-10-20T12:00:00.123456Z");
        List<UUID> ids = java.util.stream.IntStream.rangeClosed(1, 4)
                .mapToObj(i -> UUID.fromString("73000000-0000-0000-0000-%012d".formatted(i)))
                .toList();
        for (UUID id : ids) insertQueryTimeline(TENANT_A, ticketId, id, "COMMENT_ADDED", sameTime);
        insertQueryTimeline(TENANT_A, ticketId, UUID.randomUUID(), "CREATED", sameTime);
        insertQueryTimeline(TENANT_A, ticketId, UUID.randomUUID(), "COMMENT_ADDED", sameTime.minusSeconds(1));
        insertQueryTimeline(TENANT_A, ticketId, UUID.randomUUID(), "COMMENT_ADDED", sameTime.plusSeconds(1));
        insertQueryTimeline(TENANT_B, ticketId, UUID.randomUUID(), "COMMENT_ADDED", sameTime);
        insertQueryTimeline(TENANT_A, UUID.randomUUID(), UUID.randomUUID(), "COMMENT_ADDED", sameTime);
        var filters = new org.springframework.util.LinkedMultiValueMap<String, String>();
        filters.add("eventType", "COMMENT_ADDED");
        filters.add("occurredFrom", sameTime.toString());
        filters.add("occurredTo", sameTime.toString());
        for (String sort : List.of("occurredAt", "-occurredAt")) {
            List<UUID> order = new java.util.ArrayList<>(ids);
            if (sort.startsWith("-")) java.util.Collections.reverse(order);
            assertThat(readQueryPages("/api/v1/tickets/" + ticketId + "/timeline", requesterToken(),
                    filters, sort, "timelineId"))
                    .containsExactlyElementsOf(order.stream().map(UUID::toString).toList());
        }
    }

    /**
     * 沿响应游标连续读取全部测试页，核验顺序、筛选及同时间边界。
     */
    private List<String> readQueryPages(String path, String token,
            org.springframework.util.MultiValueMap<String, String> filters, String sort, String idField)
            throws Exception {
        List<String> ids = new java.util.ArrayList<>();
        String next = null;
        for (int page = 0; page < 10; page++) {
            var params = new org.springframework.util.LinkedMultiValueMap<String, String>(filters);
            params.set("sort", sort);
            params.set("pageSize", "2");
            if (next != null) params.set("pageToken", next);
            String json = mockMvc.perform(get(path).header("Authorization", "Bearer " + token).params(params))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<String> pageIds = JsonPath.read(json, "$.data[*]." + idField);
            assertThat(pageIds).hasSizeLessThanOrEqualTo(2);
            ids.addAll(pageIds);
            java.util.Map<String, Object> pageMeta = JsonPath.read(json, "$.meta.page");
            next = (String) pageMeta.get("nextPageToken");
            if (next == null) return ids;
            assertThat(next).isNotBlank();
        }
        throw new AssertionError("Query pagination failed to terminate within ten pages");
    }

    /**
     * 保存本场景前置业务事实，使用参数控制租户、身份、状态及时间边界。
     */
    private void insertQueryTicket(UUID tenant, UUID id, String state, String origin, UUID catalog,
                                   UUID requester, UUID assignee, Instant time) {
        jdbc.update("""
                INSERT INTO ticket (tenant_id, ticket_id, ticket_no, creation_intent_key, origin_type,
                    catalog_item_id, requester_user_id, subject, description, status, assignee_user_id,
                    created_at, updated_at, created_by_type, updated_by_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'query fixture', 'query fixture', ?, ?, ?, ?, 'SERVICE', 'SERVICE')
                """, Uuids.toBytes(tenant), Uuids.toBytes(id), id.toString().replace("-", ""),
                java.nio.ByteBuffer.allocate(32).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(), origin, Uuids.toBytes(catalog),
                Uuids.toBytes(requester), state, Uuids.toBytes(assignee), Timestamp.from(time), Timestamp.from(time));
    }

    /**
     * 保存本场景前置业务事实，使用参数控制租户、身份、状态及时间边界。
     */
    private void insertQueryComment(UUID tenant, UUID ticketId, UUID id, Instant time) {
        jdbc.update("""
                INSERT INTO ticket_comment (tenant_id, comment_id, ticket_id, body, correlation_id,
                    created_at, created_by_type, created_by_id)
                VALUES (?, ?, ?, 'query comment', ?, ?, 'USER', ?)
                """, Uuids.toBytes(tenant), Uuids.toBytes(id), Uuids.toBytes(ticketId),
                Uuids.toBytes(UUID.randomUUID()), Timestamp.from(time), Uuids.toBytes(REQUESTER_A));
    }

    /**
     * 保存本场景前置业务事实，使用参数控制租户、身份、状态及时间边界。
     */
    private void insertQueryTimeline(UUID tenant, UUID ticketId, UUID id, String event, Instant time) {
        jdbc.update("""
                INSERT INTO ticket_timeline_entry (tenant_id, timeline_id, ticket_id, effect_key,
                    event_type, summary, actor_type, actor_id, occurred_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, 'query timeline', 'SERVICE', NULL, ?, ?)
                """, Uuids.toBytes(tenant), Uuids.toBytes(id), Uuids.toBytes(ticketId),
                java.nio.ByteBuffer.allocate(32).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(), event,
                Timestamp.from(time), Uuids.toBytes(UUID.randomUUID()));
    }

    // ----- helpers -------------------------------------------------------------------------

    /**
     * 通过真实 HTTP 接口创建测试工单并返回其标识，供后续生命周期场景使用。
     */
    private UUID createTicket(UUID catalogItemId, String idempotencyKey) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + requesterToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content("""
                                {"catalogItemId":"%s","subject":"测试工单","description":"测试描述"}
                                """.formatted(catalogItemId)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.data.ticketId"));
    }

    /**
     * 通过 HTTP 分派测试工单，断言接口接受目标处理人及当前版本。
     */
    private void assignTicket(UUID ticketId, UUID assignee, String idempotencyKey) throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/assign")
                        .header("Authorization", "Bearer " + adminToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .header("If-Match", "\"lv:0\"")
                        .contentType("application/json")
                        .content("{\"assigneeUserId\":\"%s\"}".formatted(assignee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lockVersion").value(1));
    }

    /**
     * 通过 HTTP 受理工单，校验认证身份、幂等键与预期锁版本。
     */
    private void acceptTicket(UUID ticketId, String token, String idempotencyKey,
                              long expectedLockVersion) throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/accept")
                        .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey)
                .header("If-Match", "\"lv:%d\"".formatted(expectedLockVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.lockVersion").value(expectedLockVersion + 1));
    }

    /**
     * 通过 HTTP 解决测试工单并断言成功，供关闭和重开场景使用。
     */
    private void resolveTicket(UUID ticketId, String token, String idempotencyKey,
                               long expectedLockVersion, String summary) throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + ticketId + "/resolve")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .header("If-Match", "\"lv:%d\"".formatted(expectedLockVersion))
                        .contentType("application/json")
                        .content("{\"resolutionSummary\":\"%s\"}".formatted(summary)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.lockVersion").value(expectedLockVersion + 1));
    }

    /**
     * 签发当前租户申请人令牌，供验证申请人可见范围。
     */
    private String requesterToken() {
        return token(REQUESTER_A, RoleCode.REQUESTER, PermissionCode.CATALOG_READ_ACTIVE,
                PermissionCode.TICKET_CREATE, PermissionCode.TICKET_READ_OWN,
                PermissionCode.TICKET_COMMENT_OWN, PermissionCode.TICKET_CLOSE_OWN,
                PermissionCode.TICKET_REOPEN_OWN);
    }

    /**
     * 签发另一申请人令牌，供验证同租户不同用户隔离。
     */
    private String requesterBToken() {
        return token(REQUESTER_B, RoleCode.REQUESTER, PermissionCode.CATALOG_READ_ACTIVE,
                PermissionCode.TICKET_CREATE, PermissionCode.TICKET_READ_OWN,
                PermissionCode.TICKET_COMMENT_OWN, PermissionCode.TICKET_CLOSE_OWN,
                PermissionCode.TICKET_REOPEN_OWN);
    }

    /**
     * 签发处理人令牌，供分派、受理及处理范围测试。
     */
    private String agentToken() {
        return token(AGENT_A, RoleCode.AGENT, PermissionCode.TICKET_READ_ASSIGNED,
                PermissionCode.TICKET_ACCEPT_ASSIGNED, PermissionCode.TICKET_TRANSFER_ASSIGNED,
                PermissionCode.TICKET_COMMENT_ASSIGNED, PermissionCode.TICKET_RESOLVE_ASSIGNED);
    }

    /**
     * 签发第二处理人令牌，供转派与处理人变更场景。
     */
    private String agent2Token() {
        return token(AGENT_A2, RoleCode.AGENT, PermissionCode.TICKET_READ_ASSIGNED,
                PermissionCode.TICKET_ACCEPT_ASSIGNED, PermissionCode.TICKET_TRANSFER_ASSIGNED,
                PermissionCode.TICKET_COMMENT_ASSIGNED, PermissionCode.TICKET_RESOLVE_ASSIGNED);
    }

    /**
     * 签发管理员令牌，供管理操作与全租户可见范围测试。
     */
    private String adminToken() {
        return token(ADMIN_A, RoleCode.SERVICE_DESK_ADMIN, PermissionCode.CATALOG_READ_TENANT,
                PermissionCode.CATALOG_UPDATE_MINIMAL, PermissionCode.TICKET_READ_TENANT,
                PermissionCode.TICKET_ASSIGN_TENANT, PermissionCode.TICKET_TRANSFER_TENANT);
    }

    /**
     * 按指定主体、租户与权限签发测试 JWT，走服务真实认证路径。
     */
    private String token(UUID user, RoleCode role, PermissionCode... permissions) {
        return ISSUER.issueHuman(TENANT_A, user, Set.of(role), Set.of(permissions)).token();
    }

    /**
     * 保存本场景前置业务事实，使用参数控制租户、身份、状态及时间边界。
     */
    private void insertSlaPolicy(UUID tenant, UUID id, String code, long firstResponse,
                                 long resolution) {
        jdbc.update("""
                INSERT INTO sla_policy (tenant_id, sla_policy_id, policy_code, policy_name,
                    first_response_seconds, resolution_seconds, first_response_reminder_before_seconds,
                    resolution_reminder_before_seconds, is_incident_default, status, lock_version,
                    created_by_type, updated_by_type)
                VALUES (?, ?, ?, ?, ?, ?, 300, 3600, 0, 'ACTIVE', 0, 'SERVICE', 'SERVICE')
                """, Uuids.toBytes(tenant), Uuids.toBytes(id), code, code, firstResponse, resolution);
    }

    /**
     * 保存本场景前置业务事实，使用参数控制租户、身份、状态及时间边界。
     */
    private void insertCatalog(UUID tenant, UUID id, String code, String risk, boolean approval,
                               UUID workflowDef, UUID workflowVersion, Integer versionNo) {
        jdbc.update("""
                INSERT INTO service_catalog_item (tenant_id, catalog_item_id, item_code, item_name,
                    description, risk_level, requires_approval, workflow_definition_id,
                    workflow_version_id, workflow_version_no, sla_policy_id, status, lock_version,
                    created_by_type, updated_by_type)
                VALUES (?, ?, ?, ?, '演示目录项', ?, ?, ?, ?, ?, ?, 'ACTIVE', 0, 'SERVICE', 'SERVICE')
                """, Uuids.toBytes(tenant), Uuids.toBytes(id), code, code, risk, approval,
                Uuids.toBytes(workflowDef), Uuids.toBytes(workflowVersion), versionNo,
                Uuids.toBytes(SLA_A));
    }

    /**
     * 生成测试专用非对称密钥对，隔离令牌签名与真实环境密钥。
     */
    private static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("RSA").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 把测试公钥编码为 PEM，供应用配置中的 JWT 验证使用。
     */
    private static String publicKeyPem() {
        String nl = System.lineSeparator();
        String base64 = Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----" + nl + base64 + nl + "-----END PUBLIC KEY-----";
    }

    /**
     * 返回测试游标签名密钥，确保分页令牌可按真实协议生成和验证。
     */
    private static String cursorHmacKey() {
        return Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 用于该测试的内部替身，提供可控制的时钟、调度或依赖行为。
     */
    @TestConfiguration
    static class EligibilityStubConfiguration {

        /**
         * 装配工单的 stubEligibilityChecker 组件，将配置与依赖交由 Spring 管理。
         */
        @Bean
        @Primary
        AssigneeEligibilityChecker stubEligibilityChecker() {
            return (tenantId, userId) -> TENANT_A.equals(tenantId) && !INELIGIBLE.equals(userId);
        }
    }
}
