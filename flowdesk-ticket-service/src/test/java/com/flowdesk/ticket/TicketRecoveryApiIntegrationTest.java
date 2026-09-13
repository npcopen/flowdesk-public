package com.flowdesk.ticket;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.RoleCode;
import com.flowdesk.common.security.JwtIssuer;
import com.flowdesk.ticket.incident.IncidentConsumeResult;
import com.flowdesk.ticket.incident.IncidentMessageContract;
import com.flowdesk.ticket.incident.IncidentMessageConsumer;
import com.flowdesk.ticket.incident.IncidentTransportMessage;
import com.flowdesk.ticket.repository.TicketRepository.Uuids;
import com.flowdesk.ticket.reverse.WorkflowReverseContract;
import com.flowdesk.ticket.service.IdempotencyCodec;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TicketRecoveryApiIntegrationTest {
    private static final DateTimeFormatter MICROS = new DateTimeFormatterBuilder()
            .appendInstant(6).toFormatter();
    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ADMIN = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID REQUESTER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID DEFINITION = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID VERSION = UUID.fromString("50000000-0000-0000-0000-000000000002");
    private static final UUID INCIDENT_POLICY = UUID.fromString("50000000-0000-0000-0000-000000000003");
    private static final KeyPair KEYS = keyPair();
    private static final JwtIssuer ISSUER = new JwtIssuer(KEYS.getPrivate(),
            "flowdesk-iam-service", "flowdesk-api", "flowdesk-internal", 900, 300);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IncidentMessageConsumer incidentConsumer;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.security.jwt.public-key-pem", TicketRecoveryApiIntegrationTest::pem);
        registry.add("flowdesk.security.cursor-token.hmac-key", TicketRecoveryApiIntegrationTest::cursorKey);
        registry.add("flowdesk.ticket.workflow-reverse.entry.enabled", () -> "true");
        registry.add("flowdesk.ticket.workflow-reverse.consumer.enabled", () -> "false");
        registry.add("flowdesk.ticket.incident-entry.enabled", () -> "true");
        registry.add("flowdesk.ticket.incident-consumer.enabled", () -> "false");
        registry.add("FLOWDESK_TICKET_DB_PASSWORD", () -> "");
        registry.add("spring.cloud.service-registry.auto-registration.enabled", () -> "false");
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:ticket-recovery-api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:schema-test.sql");
    }

    @BeforeEach
    void clean() {
        for (String table : List.of("message_recovery", "inbox_message", "outbox_event",
                "audit_record", "sla_clock", "incident_ticket_mapping", "ticket_timeline_entry",
                "workflow_start_link", "ticket", "ticket_number_counter", "service_catalog_item", "sla_policy")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void incidentInboxAndAuthoritativeResultOutboxUseControlledSamePathReplay() throws Exception {
        UUID incident = UUID.randomUUID();
        UUID inbox = UUID.randomUUID();
        UUID recovery = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        byte[] createIntent = IdempotencyCodec.derivedKey(
                IncidentMessageContract.CREATE_REQUESTED.intentNamespace(), TENANT_A, incident);
        byte[] body = ("{\"schemaVersion\":\"1\",\"incidentId\":\"" + incident
                + "\",\"createTicketIntentKey\":\"" + HexFormat.of().formatHex(createIntent)
                + "\",\"fingerprint\":\"host=m7-recovery\",\"generationNo\":1,"
                + "\"cycleRef\":\"cycle-recovery\",\"summary\":\"replay create\","
                + "\"openedAt\":\"2026-08-26T03:00:00.000000Z\"}")
                .getBytes(StandardCharsets.UTF_8);
        Instant received = Instant.parse("2026-08-26T03:00:01.000000Z");
        seedIncidentPolicy(received.minusSeconds(60));
        jdbc.update("""
                INSERT INTO inbox_message
                  (tenant_id,inbox_id,producer_service,message_id,business_intent_key,
                   contract_ref,contract_version,body_bytes,body_sha256,correlation_id,
                   causation_id,occurred_at,status,attempt_count,last_error_code,
                   first_received_at,last_attempt_at,lock_version,updated_by_type)
                VALUES (?,?,?,?,?,?,1,?,?,?,?,?,'RECOVERY_REQUIRED',6,
                        'BROKER_RETRIES_EXHAUSTED',?,?,0,'SERVICE')
                """, Uuids.toBytes(TENANT_A), Uuids.toBytes(inbox),
                IncidentMessageContract.ALERT_PRODUCER, Uuids.toBytes(message), createIntent,
                IncidentMessageContract.CREATE_REQUESTED.contractRef(), body, sha256(body),
                Uuids.toBytes(correlation), null, Timestamp.from(received), Timestamp.from(received),
                Timestamp.from(received));
        seedRecovery(TENANT_A, recovery, "INBOX", inbox, createIntent, 6, received);

        mockMvc.perform(post("/api/v1/ticket-message-recoveries/" + recovery + "/replays")
                        .header("Authorization", bearer(adminToken()))
                        .header("Idempotency-Key", "ticket-incident-inbox-replay-0001")
                        .header("If-Match", "\"lv:0\"")
                        .contentType("application/json")
                        .content("{\"reason\":\"incident dependency restored\"}"))
                .andExpect(status().isAccepted());

        awaitState(recovery, "RESOLVED");
        assertThat(count("ticket")).isEqualTo(1);
        assertThat(count("incident_ticket_mapping")).isEqualTo(1);
        assertThat(count("ticket_timeline_entry")).isEqualTo(1);
        assertThat(count("sla_clock")).isEqualTo(2);
        assertThat(count("outbox_event")).isEqualTo(1);

        UUID resultOutbox = Uuids.toUuid(jdbc.queryForObject(
                "SELECT outbox_id FROM outbox_event WHERE contract_ref=?", byte[].class,
                IncidentMessageContract.ESTABLISHED_CONTRACT_REF));
        byte[] resultIntent = jdbc.queryForObject(
                "SELECT business_intent_key FROM outbox_event WHERE outbox_id=?", byte[].class,
                Uuids.toBytes(resultOutbox));
        jdbc.update("UPDATE outbox_event SET status='RECOVERY_REQUIRED',attempt_count=8,"
                        + "last_error_code='BROKER_RESULT_UNKNOWN' WHERE outbox_id=?",
                Uuids.toBytes(resultOutbox));
        UUID outboxRecovery = UUID.randomUUID();
        seedRecovery(TENANT_A, outboxRecovery, "OUTBOX", resultOutbox, resultIntent, 8, null);

        mockMvc.perform(post("/api/v1/ticket-message-recoveries/" + outboxRecovery + "/replays")
                        .header("Authorization", bearer(adminToken()))
                        .header("Idempotency-Key", "ticket-incident-outbox-replay-0001")
                        .header("If-Match", "\"lv:0\"")
                        .contentType("application/json")
                        .content("{\"reason\":\"result publisher restored\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.state").value("REPLAYING"));
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE outbox_id=?",
                String.class, Uuids.toBytes(resultOutbox))).isEqualTo("PENDING");
        assertThat(count("ticket")).isEqualTo(1);
        assertThat(count("incident_ticket_mapping")).isEqualTo(1);
    }

    @Test
    void lateIncidentDeliveryAndManualReplayConvergeToOneRecoveryEffect() throws Exception {
        UUID incident = UUID.randomUUID();
        byte[] createIntent = IdempotencyCodec.derivedKey(
                IncidentMessageContract.CREATE_REQUESTED.intentNamespace(), TENANT_A, incident);
        Instant openedAt = Instant.parse("2026-08-26T05:00:00.000000Z");
        seedIncidentPolicy(openedAt.minusSeconds(60));

        byte[] createBody = ("{\"schemaVersion\":\"1\",\"incidentId\":\"" + incident
                + "\",\"createTicketIntentKey\":\"" + HexFormat.of().formatHex(createIntent)
                + "\",\"fingerprint\":\"host=m7-late-race\",\"generationNo\":1,"
                + "\"cycleRef\":\"cycle-late-race\",\"summary\":\"late race create\","
                + "\"openedAt\":\"2026-08-26T05:00:00.000000Z\"}")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(incidentConsumer.consume(incidentDelivery(
                IncidentMessageContract.CREATE_REQUESTED, incident, UUID.randomUUID(),
                UUID.randomUUID(), createIntent, createBody, openedAt)).disposition())
                .isEqualTo(IncidentConsumeResult.Disposition.ACK);

        UUID inbox = UUID.randomUUID();
        UUID recovery = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        UUID recoveryEvent = UUID.randomUUID();
        Instant recoveredAt = openedAt.plusSeconds(60);
        byte[] recoveryIntent = IdempotencyCodec.derivedKey(
                IncidentMessageContract.RECOVERED.intentNamespace(), TENANT_A, incident);
        byte[] recoveryBody = ("{\"schemaVersion\":\"1\",\"incidentId\":\"" + incident
                + "\",\"createTicketIntentKey\":\"" + HexFormat.of().formatHex(createIntent)
                + "\",\"recoveryEventId\":\"" + recoveryEvent
                + "\",\"recoveredAt\":\"2026-08-26T05:01:00.000000Z\"}")
                .getBytes(StandardCharsets.UTF_8);
        jdbc.update("""
                INSERT INTO inbox_message
                  (tenant_id,inbox_id,producer_service,message_id,business_intent_key,
                   contract_ref,contract_version,body_bytes,body_sha256,correlation_id,
                   causation_id,occurred_at,status,attempt_count,last_error_code,
                   first_received_at,last_attempt_at,lock_version,updated_by_type)
                VALUES (?,?,?,?,?,?,1,?,?,?,?,?,'RECOVERY_REQUIRED',6,
                        'BROKER_RETRIES_EXHAUSTED',?,?,0,'SERVICE')
                """, Uuids.toBytes(TENANT_A), Uuids.toBytes(inbox),
                IncidentMessageContract.ALERT_PRODUCER, Uuids.toBytes(message), recoveryIntent,
                IncidentMessageContract.RECOVERED.contractRef(), recoveryBody, sha256(recoveryBody),
                Uuids.toBytes(correlation), null, Timestamp.from(recoveredAt),
                Timestamp.from(recoveredAt), Timestamp.from(recoveredAt));
        seedRecovery(TENANT_A, recovery, "INBOX", inbox, recoveryIntent, 6, recoveredAt);
        IncidentTransportMessage lateDelivery = incidentDelivery(
                IncidentMessageContract.RECOVERED, incident, message, correlation,
                recoveryIntent, recoveryBody, recoveredAt);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> replayStatus = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/v1/ticket-message-recoveries/" + recovery
                                + "/replays")
                                .header("Authorization", bearer(adminToken()))
                                .header("Idempotency-Key", "ticket-late-race-" + recovery)
                                .header("If-Match", "\"lv:0\"")
                                .contentType("application/json")
                                .content("{\"reason\":\"race late broker success\"}"))
                        .andReturn().getResponse().getStatus();
            });
            Future<IncidentConsumeResult> lateResult = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return incidentConsumer.consume(lateDelivery);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(replayStatus.get(15, TimeUnit.SECONDS)).isIn(202, 409);
            assertThat(lateResult.get(15, TimeUnit.SECONDS).disposition())
                    .isEqualTo(IncidentConsumeResult.Disposition.ACK);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        awaitState(recovery, "RESOLVED");
        assertThat(jdbc.queryForMap("""
                SELECT status,attempt_count FROM inbox_message
                WHERE tenant_id=? AND inbox_id=?
                """, Uuids.toBytes(TENANT_A), Uuids.toBytes(inbox)))
                .satisfies(row -> {
                    assertThat(row.get("STATUS")).isEqualTo("PROCESSED");
                    assertThat(((Number) row.get("ATTEMPT_COUNT")).intValue()).isEqualTo(7);
                });
        assertThat(jdbc.queryForObject("""
                SELECT replay_count FROM message_recovery
                WHERE tenant_id=? AND recovery_id=?
                """, Integer.class, Uuids.toBytes(TENANT_A), Uuids.toBytes(recovery)))
                .isBetween(0, 1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ticket_timeline_entry
                WHERE tenant_id=? AND event_type='INCIDENT_RECOVERED'
                """, Integer.class, Uuids.toBytes(TENANT_A))).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_record
                WHERE tenant_id=? AND action_code='INCIDENT_RECOVERY_TIMELINE_APPENDED'
                """, Integer.class, Uuids.toBytes(TENANT_A))).isEqualTo(1);
        assertThat(count("ticket")).isEqualTo(1);
        assertThat(count("incident_ticket_mapping")).isEqualTo(1);
        assertThat(count("sla_clock")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM ticket", String.class))
                .isEqualTo("PENDING_ASSIGNMENT");
    }

    @Test
    void ticketOutboxReplayQueuesOnlyTheOriginalFactAndIsTenantScopedAndIdempotent()
            throws Exception {
        UUID ticket = UUID.randomUUID();
        UUID outbox = UUID.randomUUID();
        UUID recovery = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        byte[] body = "{\"schemaVersion\":\"1\"}".getBytes(StandardCharsets.UTF_8);
        seedTicket(TENANT_A, ticket);
        seedLink(TENANT_A, ticket, bytes(1), "RECOVERY_REQUIRED", null);
        seedOutbox(TENANT_A, ticket, outbox, message, body);
        seedRecovery(TENANT_A, recovery, "OUTBOX", outbox, bytes(2), 8, null);
        seedRecovery(TENANT_B, UUID.randomUUID(), "OUTBOX", UUID.randomUUID(), bytes(3), 8, null);

        mockMvc.perform(get("/api/v1/ticket-message-recoveries")
                        .header("Authorization", bearer(adminToken()))
                        .param("direction", "OUTBOX").param("state", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].recoveryId").value(recovery.toString()))
                .andExpect(jsonPath("$.data[0].businessIntentDigest").isString())
                .andExpect(jsonPath("$.data[0].bodyBytes").doesNotExist());

        mockMvc.perform(get("/api/v1/ticket-message-recoveries/" + recovery)
                        .header("Authorization", bearer(requesterToken())))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/ticket-message-recoveries/" + recovery)
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"lv:0\""));

        String command = "{\"reason\":\"重新发送原始启动事实\"}";
        mockMvc.perform(post("/api/v1/ticket-message-recoveries/" + recovery + "/replays")
                        .header("Authorization", bearer(adminToken()))
                        .header("Idempotency-Key", "ticket-outbox-replay-0001")
                        .header("If-Match", "\"lv:0\"")
                        .contentType("application/json").content(command))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(header().string("ETag", "\"lv:1\""))
                .andExpect(jsonPath("$.data.state").value("REPLAYING"))
                .andExpect(jsonPath("$.data.replayCount").value(1));

        assertThat(jdbc.queryForMap("""
                SELECT status,attempt_count,message_id,body_sha256
                FROM outbox_event WHERE tenant_id=? AND outbox_id=?
                """, Uuids.toBytes(TENANT_A), Uuids.toBytes(outbox)))
                .satisfies(row -> {
                    assertThat(row.get("STATUS")).isEqualTo("PENDING");
                    assertThat(((Number) row.get("ATTEMPT_COUNT")).intValue()).isZero();
                    assertThat((byte[]) row.get("MESSAGE_ID")).containsExactly(Uuids.toBytes(message));
                    assertThat((byte[]) row.get("BODY_SHA256")).containsExactly(sha256(body));
                });
        assertThat(jdbc.queryForObject("""
                SELECT status FROM workflow_start_link WHERE tenant_id=? AND ticket_id=?
                """, String.class, Uuids.toBytes(TENANT_A), Uuids.toBytes(ticket)))
                .isEqualTo("REQUESTED");

        mockMvc.perform(post("/api/v1/ticket-message-recoveries/" + recovery + "/replays")
                        .header("Authorization", bearer(adminToken()))
                        .header("Idempotency-Key", "ticket-outbox-replay-0001")
                        .header("If-Match", "\"lv:0\"")
                        .contentType("application/json").content(command))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.data.replayCount").value(1));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_record
                WHERE tenant_id=? AND action_code='TICKET_RECOVERY_REPLAY_REQUESTED'
                """, Integer.class, Uuids.toBytes(TENANT_A))).isEqualTo(1);
    }

    @Test
    void ticketInboxReplayUsesPersistedBytesAndConvergesThroughTheNormalConsumer()
            throws Exception {
        UUID ticket = UUID.randomUUID();
        UUID inbox = UUID.randomUUID();
        UUID recovery = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        UUID causation = UUID.randomUUID();
        byte[] startIntent = bytes(9);
        Instant requested = Instant.parse("2026-08-24T03:00:00.000000Z");
        Instant started = Instant.parse("2026-08-24T03:01:00.123456Z");
        byte[] intent = IdempotencyCodec.derivedKey(
                WorkflowReverseContract.INSTANCE_ESTABLISHED.intentNamespace(), TENANT_A, ticket);
        byte[] body = ("{\"schemaVersion\":\"1\",\"ticketId\":\"" + ticket
                + "\",\"workflowInstanceId\":\"" + instance
                + "\",\"startIntentKey\":\"" + HexFormat.of().formatHex(startIntent)
                + "\",\"workflowDefinitionId\":\"" + DEFINITION
                + "\",\"workflowVersionId\":\"" + VERSION
                + "\",\"versionNo\":2,\"instanceStatus\":\"RUNNING\","
                + "\"startedAt\":\"2026-08-24T03:01:00.123456Z\"}")
                .getBytes(StandardCharsets.UTF_8);
        seedTicket(TENANT_A, ticket);
        seedLink(TENANT_A, ticket, startIntent, "REQUESTED", requested);
        jdbc.update("""
                INSERT INTO inbox_message
                  (tenant_id,inbox_id,producer_service,message_id,business_intent_key,
                   contract_ref,contract_version,body_bytes,body_sha256,correlation_id,
                   causation_id,occurred_at,status,attempt_count,last_error_code,
                   first_received_at,last_attempt_at,lock_version,updated_by_type)
                VALUES (?,?,?,?,?,'flowdesk.workflow.instance-established.v1',1,?,?,?,?,?,
                        'RECOVERY_REQUIRED',6,'BROKER_RETRIES_EXHAUSTED',?,?,0,'SERVICE')
                """, Uuids.toBytes(TENANT_A), Uuids.toBytes(inbox),
                WorkflowReverseContract.PRODUCER, Uuids.toBytes(message), intent, body,
                sha256(body), Uuids.toBytes(correlation), Uuids.toBytes(causation),
                Timestamp.from(started), Timestamp.from(started), Timestamp.from(started));
        seedRecovery(TENANT_A, recovery, "INBOX", inbox, intent, 6, started);

        mockMvc.perform(post("/api/v1/ticket-message-recoveries/" + recovery + "/replays")
                        .header("Authorization", bearer(adminToken()))
                        .header("Idempotency-Key", "ticket-inbox-replay-0001")
                        .header("If-Match", "\"lv:0\"")
                        .contentType("application/json")
                        .content("{\"reason\":\"资格和依赖已恢复\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotency-Replayed", "false"));

        awaitState(recovery, "RESOLVED");
        assertThat(jdbc.queryForMap("""
                SELECT status,attempt_count,body_bytes,body_sha256
                FROM inbox_message WHERE tenant_id=? AND inbox_id=?
                """, Uuids.toBytes(TENANT_A), Uuids.toBytes(inbox)))
                .satisfies(row -> {
                    assertThat(row.get("STATUS")).isEqualTo("PROCESSED");
                    assertThat(((Number) row.get("ATTEMPT_COUNT")).intValue()).isEqualTo(7);
                    assertThat((byte[]) row.get("BODY_BYTES")).containsExactly(body);
                    assertThat((byte[]) row.get("BODY_SHA256")).containsExactly(sha256(body));
                });
        assertThat(jdbc.queryForMap("""
                SELECT status,workflow_instance_id FROM workflow_start_link
                WHERE tenant_id=? AND ticket_id=?
                """, Uuids.toBytes(TENANT_A), Uuids.toBytes(ticket)))
                .satisfies(row -> {
                    assertThat(row.get("STATUS")).isEqualTo("ESTABLISHED");
                    assertThat((byte[]) row.get("WORKFLOW_INSTANCE_ID"))
                            .containsExactly(Uuids.toBytes(instance));
                });
    }

    private void seedTicket(UUID tenant, UUID ticket) {
        jdbc.update("""
                INSERT INTO ticket
                  (tenant_id,ticket_id,ticket_no,creation_intent_key,origin_type,
                   requester_user_id,subject,description,status,workflow_definition_id,
                   workflow_version_id,workflow_version_no,lock_version,
                   created_by_type,updated_by_type)
                VALUES (?,?,?,?,'MANUAL',?,'recovery','recovery','AWAITING_APPROVAL',?,?,2,0,
                        'USER','USER')
                """, Uuids.toBytes(tenant), Uuids.toBytes(ticket),
                "FD-R-" + ticket.toString().substring(0, 8),
                bytes(4), Uuids.toBytes(REQUESTER), Uuids.toBytes(DEFINITION), Uuids.toBytes(VERSION));
    }

    private void seedIncidentPolicy(Instant now) {
        jdbc.update("""
                INSERT INTO sla_policy
                  (tenant_id,sla_policy_id,policy_code,policy_name,first_response_seconds,
                   resolution_seconds,first_response_reminder_before_seconds,
                   resolution_reminder_before_seconds,is_incident_default,status,lock_version,
                   created_at,created_by_type,created_by_id,updated_at,updated_by_type,updated_by_id)
                VALUES (?,?,'INCIDENT','Incident',600,3600,60,300,1,'ACTIVE',0,
                        ?,'SERVICE',NULL,?,'SERVICE',NULL)
                """, Uuids.toBytes(TENANT_A), Uuids.toBytes(INCIDENT_POLICY),
                Timestamp.from(now), Timestamp.from(now));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void seedLink(UUID tenant, UUID ticket, byte[] intent, String status, Instant requested) {
        Instant at = requested == null ? Instant.parse("2026-08-24T01:00:00Z") : requested;
        jdbc.update("""
                INSERT INTO workflow_start_link
                  (tenant_id,ticket_id,start_intent_key,status,requested_at,
                   recovery_required_at,recovery_reason_code,retry_count,lock_version,
                   created_by_type,updated_by_type)
                VALUES (?,?,?,?,?,?,?,?,0,'SERVICE','SERVICE')
                """, Uuids.toBytes(tenant), Uuids.toBytes(ticket), intent, status,
                Timestamp.from(at), "RECOVERY_REQUIRED".equals(status) ? Timestamp.from(at) : null,
                "RECOVERY_REQUIRED".equals(status) ? "BROKER_SEND_FAILED" : null,
                "RECOVERY_REQUIRED".equals(status) ? 8 : 0);
    }

    private void seedOutbox(UUID tenant, UUID ticket, UUID outbox, UUID message, byte[] body) {
        jdbc.update("""
                INSERT INTO outbox_event
                  (tenant_id,outbox_id,message_id,publication_key,business_intent_key,
                   aggregate_type,aggregate_id,contract_ref,contract_version,body_bytes,
                   body_sha256,correlation_id,status,attempt_count,next_attempt_at,
                   last_error_code,lock_version,created_by_type,updated_by_type)
                VALUES (?,?,?,?,?,'TICKET',?,'flowdesk.workflow.start.v1',1,?,?,?,
                        'RECOVERY_REQUIRED',8,CURRENT_TIMESTAMP,'BROKER_SEND_FAILED',0,
                        'SERVICE','SERVICE')
                """, Uuids.toBytes(tenant), Uuids.toBytes(outbox), Uuids.toBytes(message), bytes(5),
                bytes(6), Uuids.toBytes(ticket), body, sha256(body), Uuids.toBytes(UUID.randomUUID()));
    }

    private void seedRecovery(UUID tenant, UUID recovery, String direction, UUID source,
                              byte[] intent, int attempts, Instant deadLettered) {
        jdbc.update("""
                INSERT INTO message_recovery
                  (tenant_id,recovery_id,direction,source_record_id,business_intent_key,state,
                   attempt_count,last_error_code,last_error_summary,dead_lettered_at,
                   recovery_required_at,replay_count,lock_version,updated_by_type)
                VALUES (?,?,?,?,?,'OPEN',?,'BROKER_RETRIES_EXHAUSTED','sanitized',?,
                        CURRENT_TIMESTAMP,0,0,'SERVICE')
                """, Uuids.toBytes(tenant), Uuids.toBytes(recovery), direction,
                Uuids.toBytes(source), intent, attempts,
                deadLettered == null ? null : Timestamp.from(deadLettered));
    }

    private IncidentTransportMessage incidentDelivery(
            IncidentMessageContract.Binding binding, UUID incident, UUID message,
            UUID correlation, byte[] intent, byte[] body, Instant occurredAt) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("fd_envelope_version", "1");
        properties.put("fd_event_type", binding.eventType());
        properties.put("fd_contract_ref", binding.contractRef());
        properties.put("fd_contract_version", "1");
        properties.put("fd_producer", IncidentMessageContract.ALERT_PRODUCER);
        properties.put("fd_tenant_id", TENANT_A.toString());
        properties.put("fd_message_id", message.toString());
        properties.put("fd_business_intent_key", HexFormat.of().formatHex(intent));
        properties.put("fd_aggregate_type", "INCIDENT");
        properties.put("fd_aggregate_id", incident.toString());
        properties.put("fd_correlation_id", correlation.toString());
        properties.put("fd_occurred_at", MICROS.format(occurredAt));
        properties.put("fd_body_sha256", HexFormat.of().formatHex(sha256(body)));
        return new IncidentTransportMessage(binding.topic(), binding.consumerGroup(),
                message.toString(), null, body, properties);
    }

    private void awaitState(UUID recovery, String expected) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        String state;
        do {
            state = jdbc.queryForObject("""
                    SELECT state FROM message_recovery WHERE tenant_id=? AND recovery_id=?
                    """, String.class, Uuids.toBytes(TENANT_A), Uuids.toBytes(recovery));
            if (expected.equals(state)) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Recovery did not reach " + expected + "; last state=" + state);
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
        String base64 = Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
    }
    private static String cursorKey() {
        return Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    }
}
