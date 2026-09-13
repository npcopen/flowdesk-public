package com.flowdesk.ticket;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.RoleCode;
import com.flowdesk.common.security.IssuedJwt;
import com.flowdesk.common.security.JwtIssuer;
import com.flowdesk.ticket.iam.AssigneeEligibilityChecker;
import com.flowdesk.ticket.event.TicketEventContract;
import com.flowdesk.ticket.event.WorkflowStartV1PayloadCodec;
import com.flowdesk.ticket.repository.TicketRepository.Uuids;
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
    private com.flowdesk.ticket.service.CatalogVersionClient catalogVersionClient;

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
    // ----- helpers -------------------------------------------------------------------------

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

    private String requesterToken() {
        return token(REQUESTER_A, RoleCode.REQUESTER, PermissionCode.CATALOG_READ_ACTIVE,
                PermissionCode.TICKET_CREATE, PermissionCode.TICKET_READ_OWN,
                PermissionCode.TICKET_COMMENT_OWN, PermissionCode.TICKET_CLOSE_OWN,
                PermissionCode.TICKET_REOPEN_OWN);
    }

    private String requesterBToken() {
        return token(REQUESTER_B, RoleCode.REQUESTER, PermissionCode.CATALOG_READ_ACTIVE,
                PermissionCode.TICKET_CREATE, PermissionCode.TICKET_READ_OWN,
                PermissionCode.TICKET_COMMENT_OWN, PermissionCode.TICKET_CLOSE_OWN,
                PermissionCode.TICKET_REOPEN_OWN);
    }

    private String agentToken() {
        return token(AGENT_A, RoleCode.AGENT, PermissionCode.TICKET_READ_ASSIGNED,
                PermissionCode.TICKET_ACCEPT_ASSIGNED, PermissionCode.TICKET_TRANSFER_ASSIGNED,
                PermissionCode.TICKET_COMMENT_ASSIGNED, PermissionCode.TICKET_RESOLVE_ASSIGNED);
    }

    private String agent2Token() {
        return token(AGENT_A2, RoleCode.AGENT, PermissionCode.TICKET_READ_ASSIGNED,
                PermissionCode.TICKET_ACCEPT_ASSIGNED, PermissionCode.TICKET_TRANSFER_ASSIGNED,
                PermissionCode.TICKET_COMMENT_ASSIGNED, PermissionCode.TICKET_RESOLVE_ASSIGNED);
    }

    private String adminToken() {
        return token(ADMIN_A, RoleCode.SERVICE_DESK_ADMIN, PermissionCode.CATALOG_READ_TENANT,
                PermissionCode.CATALOG_UPDATE_MINIMAL, PermissionCode.TICKET_READ_TENANT,
                PermissionCode.TICKET_ASSIGN_TENANT, PermissionCode.TICKET_TRANSFER_TENANT);
    }

    private String token(UUID user, RoleCode role, PermissionCode... permissions) {
        return ISSUER.issueHuman(TENANT_A, user, Set.of(role), Set.of(permissions)).token();
    }

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

    private static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("RSA").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String publicKeyPem() {
        String nl = System.lineSeparator();
        String base64 = Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----" + nl + base64 + nl + "-----END PUBLIC KEY-----";
    }

    private static String cursorHmacKey() {
        return Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    }

    @TestConfiguration
    static class EligibilityStubConfiguration {

        @Bean
        @Primary
        AssigneeEligibilityChecker stubEligibilityChecker() {
            return (tenantId, userId) -> TENANT_A.equals(tenantId) && !INELIGIBLE.equals(userId);
        }
    }
}
