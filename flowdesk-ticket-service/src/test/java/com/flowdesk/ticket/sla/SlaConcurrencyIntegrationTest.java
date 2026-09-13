package com.flowdesk.ticket.sla;

import com.flowdesk.common.notification.NotificationWireCodec;
import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.ticket.notification.TicketNotificationIntent;
import com.flowdesk.ticket.notification.TicketNotificationOutboxRepository;
import com.flowdesk.ticket.repository.TicketRepository;
import com.flowdesk.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** M8 scanner race: MySQL facts remain single-writer under two concurrent scans. */
@SpringBootTest(properties = "spring.main.web-application-type=none")
@Import(SlaConcurrencyIntegrationTest.FixedClockConfiguration.class)
class SlaConcurrencyIntegrationTest {
    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RECIPIENT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TICKET_A = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID TICKET_B = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID CLOCK_A = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID CLOCK_B = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID POLICY_A = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID TARGET_A = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-30T01:00:00.000000Z");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TicketRepository repository;
    @Autowired
    private TicketService ticketService;
    @Autowired
    private TicketSlaProperties properties;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private TicketNotificationOutboxRepository notificationOutboxRepository;

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.security.jwt.public-key-pem", () -> readResource("test-jwt-public-key.pem"));
        registry.add("flowdesk.security.cursor-token.hmac-key", () ->
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("FLOWDESK_TICKET_DB_PASSWORD", () -> "");
        registry.add("spring.cloud.service-registry.auto-registration.enabled", () -> "false");
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "false");
        registry.add("spring.cloud.nacos.discovery.register-enabled", () -> "false");
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:ticket-sla-concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:schema-test.sql");
    }

    @BeforeEach
    void clean() {
        for (String table : List.of("sla_trigger_recipient", "sla_qualification_snapshot",
                "sla_escalation_target", "sla_index_rebuild_lease", "sla_trigger", "sla_clock",
                "ticket_timeline_entry", "audit_record", "outbox_event", "ticket")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void twoScannersRaceOneDueClockWithoutDuplicatingEffectsOrCrossingTenants() throws Exception {
        Instant deadline = NOW.minusSeconds(1);
        seedTicketAndClock(TENANT_A, TICKET_A, CLOCK_A, deadline);
        seedQualifiedTarget(TENANT_A, RECIPIENT_A);
        seedTicketAndClock(TENANT_B, TICKET_B, CLOCK_B, NOW.plusSeconds(60));

        RecordingIndex index = new RecordingIndex();
        index.upsert(TENANT_A, CLOCK_A, "BREACH", deadline);
        TicketSlaScanner first = new TicketSlaScanner(repository, ticketService, index, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        TicketSlaScanner second = new TicketSlaScanner(repository, ticketService, index, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<TicketSlaScanner.ScanResult> one = pool.submit(() -> scan(first, ready, start));
            Future<TicketSlaScanner.ScanResult> two = pool.submit(() -> scan(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            TicketSlaScanner.ScanResult firstResult = one.get(10, TimeUnit.SECONDS);
            TicketSlaScanner.ScanResult secondResult = two.get(10, TimeUnit.SECONDS);
            assertThat(firstResult.tenantsDiscovered() + secondResult.tenantsDiscovered()).isGreaterThanOrEqualTo(4);
            assertThat(firstResult.triggered() + secondResult.triggered()).isEqualTo(1);
            assertThat(firstResult.errors() + secondResult.errors()).isZero();
        }
        finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(count("SELECT COUNT(*) FROM sla_trigger WHERE tenant_id=? AND sla_clock_id=?",
                TENANT_A, CLOCK_A)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sla_trigger_recipient WHERE tenant_id=?", TENANT_A))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE tenant_id=?"
                + " AND aggregate_type='NOTIFICATION_REQUEST' AND aggregate_id=?", TENANT_A, CLOCK_A))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sla_trigger WHERE tenant_id=?", TENANT_B)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM sla_clock WHERE tenant_id=? AND sla_clock_id=?",
                String.class, TicketRepository.Uuids.toBytes(TENANT_A), TicketRepository.Uuids.toBytes(CLOCK_A)))
                .isEqualTo("BREACHED");
        assertThat(index.due(TENANT_A, NOW, 10)).isEmpty();
    }

    @Test
    void unavailableRedisStillTriggersThroughTheBoundedMysqlSafetySweep() {
        seedTicketAndClock(TENANT_A, TICKET_A, CLOCK_A, NOW.minusSeconds(1));
        seedQualifiedTarget(TENANT_A, RECIPIENT_A);

        TicketSlaScanner scanner = new TicketSlaScanner(repository, ticketService, new UnavailableIndex(),
                properties, Clock.fixed(NOW, ZoneOffset.UTC));
        TicketSlaScanner.ScanResult result = scanner.scanOnce();

        assertThat(result.degraded()).isTrue();
        assertThat(result.triggered()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sla_trigger WHERE tenant_id=? AND sla_clock_id=?",
                TENANT_A, CLOCK_A)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE tenant_id=?"
                + " AND aggregate_type='NOTIFICATION_REQUEST' AND aggregate_id=?", TENANT_A, CLOCK_A))
                .isEqualTo(1);
    }

    @Test
    void qualificationRefreshCommitsReasonWithoutViolatingSuccessfulAuditContract() {
        seedQualifiedTarget(TENANT_A, RECIPIENT_A);
        SlaTargetQualificationService qualification = new SlaTargetQualificationService(
                repository,
                (tenantId, userId) -> new com.flowdesk.ticket.iam.SlaTargetQualificationChecker.Qualification(
                        userId, true, "ELIGIBLE", 2L, NOW),
                properties, transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));
        TenantContext context = TenantContext.ofPersistedTask(TENANT_A,
                ActorRef.service("flowdesk-ticket-sla-test"), UUID.randomUUID());

        SlaTargetQualificationService.RefreshResult result;
        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            result = qualification.refreshTenant(TENANT_A);
        }

        assertThat(result.eligible()).isTrue();
        assertThat(result.code()).isEqualTo("ELIGIBLE");
        assertThat(jdbc.queryForObject("""
                SELECT qualification_version FROM sla_escalation_target
                WHERE tenant_id=? AND target_id=?
                """, Long.class, TicketRepository.Uuids.toBytes(TENANT_A),
                TicketRepository.Uuids.toBytes(TARGET_A))).isEqualTo(2L);
        assertThat(jdbc.queryForMap("""
                SELECT outcome,reason,error_code FROM audit_record
                WHERE tenant_id=? AND action_code='SLA_TARGET_QUALIFICATION_REFRESHED'
                """, TicketRepository.Uuids.toBytes(TENANT_A)))
                .containsEntry("outcome", "SUCCEEDED")
                .containsEntry("reason", "SLA qualification refresh result: ELIGIBLE")
                .containsEntry("error_code", null);
    }

    @Test
    void expiredQualificationSkipsNotificationWithoutRollingBackTheBreach() {
        seedTicketAndClock(TENANT_A, TICKET_A, CLOCK_A, NOW.minusSeconds(1));
        seedQualifiedTarget(TENANT_A, RECIPIENT_A);
        Instant expiredCheckedAt = NOW.minusSeconds(600);
        Instant expiredValidUntil = expiredCheckedAt.plus(properties.getQualificationMaxAge());
        jdbc.update("""
                UPDATE sla_escalation_target
                SET qualification_checked_at=?,qualification_valid_until=?
                WHERE tenant_id=? AND target_id=?
                """, Timestamp.from(expiredCheckedAt), Timestamp.from(expiredValidUntil),
                TicketRepository.Uuids.toBytes(TENANT_A), TicketRepository.Uuids.toBytes(TARGET_A));
        jdbc.update("""
                UPDATE sla_qualification_snapshot
                SET checked_at=?,valid_until=?
                WHERE tenant_id=? AND target_id=?
                """, Timestamp.from(expiredCheckedAt), Timestamp.from(expiredValidUntil),
                TicketRepository.Uuids.toBytes(TENANT_A), TicketRepository.Uuids.toBytes(TARGET_A));

        TicketSlaScanner scanner = new TicketSlaScanner(repository, ticketService, new UnavailableIndex(),
                properties, Clock.fixed(NOW, ZoneOffset.UTC));
        TicketSlaScanner.ScanResult result = scanner.scanOnce();

        assertThat(result.errors()).isZero();
        assertThat(result.triggered()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM sla_clock WHERE tenant_id=? AND sla_clock_id=?",
                String.class, TicketRepository.Uuids.toBytes(TENANT_A),
                TicketRepository.Uuids.toBytes(CLOCK_A))).isEqualTo("BREACHED");
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE tenant_id=?", TENANT_A)).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT outcome,reason,error_code FROM audit_record
                WHERE tenant_id=? AND action_code='SLA_BREACH_NOTIFICATION_SKIPPED'
                """, TicketRepository.Uuids.toBytes(TENANT_A)))
                .containsEntry("outcome", "SUCCEEDED")
                .containsEntry("reason", "Notification skipped by configuration: SLA_TARGET_QUALIFICATION_INVALID")
                .containsEntry("error_code", null);
    }

    @Test
    void qualificationRecoveryRetriesATerminalBreachExactlyOnce() {
        expiredQualificationSkipsNotificationWithoutRollingBackTheBreach();
        Instant checked = NOW.minusSeconds(1), valid = checked.plus(properties.getQualificationMaxAge());
        jdbc.update("UPDATE sla_escalation_target SET qualification_checked_at=?,qualification_valid_until=?", Timestamp.from(checked), Timestamp.from(valid));
        jdbc.update("UPDATE sla_qualification_snapshot SET checked_at=?,valid_until=?", Timestamp.from(checked), Timestamp.from(valid));
        var scanner = new TicketSlaScanner(repository, ticketService, new UnavailableIndex(), properties,
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        assertThat(scanner.scanOnce().errors()).isZero();
        int notifications = count("SELECT COUNT(*) FROM outbox_event WHERE tenant_id=?", TENANT_A);
        assertThat(notifications).isPositive();
        assertThat(count("SELECT COUNT(*) FROM sla_trigger WHERE tenant_id=?", TENANT_A)).isEqualTo(1);
        assertThat(scanner.scanOnce().errors()).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE tenant_id=?", TENANT_A)).isEqualTo(notifications);
        assertThat(jdbc.queryForObject("SELECT status FROM sla_clock", String.class)).isEqualTo("BREACHED");
    }

    @Test
    void staleRedisReminderRecordsMissWithoutViolatingSuccessfulAuditContract() {
        Instant deadline = NOW.minusSeconds(1);
        seedTicketAndClock(TENANT_A, TICKET_A, CLOCK_A, deadline);
        seedQualifiedTarget(TENANT_A, RECIPIENT_A);
        RecordingIndex index = new RecordingIndex();
        index.upsert(TENANT_A, CLOCK_A, "REMINDER", deadline.minusSeconds(10));
        index.upsert(TENANT_A, CLOCK_A, "BREACH", deadline);

        TicketSlaScanner scanner = new TicketSlaScanner(repository, ticketService, index,
                properties, Clock.fixed(NOW, ZoneOffset.UTC));
        TicketSlaScanner.ScanResult result = scanner.scanOnce();

        assertThat(result.errors()).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT outcome,reason,error_code FROM audit_record
                WHERE tenant_id=? AND action_code='SLA_REMINDER_MISSED_AFTER_DEADLINE'
                """, TicketRepository.Uuids.toBytes(TENANT_A)))
                .containsEntry("outcome", "SUCCEEDED")
                .containsEntry("reason", "Reminder window closed before the scanner observed it: REMINDER_MISSED_AFTER_DEADLINE")
                .containsEntry("error_code", null);
    }

    @Test
    void notificationReplayRetainsTheFirstTraceButRejectsPayloadDrift() {
        byte[] sourceEffectKey = NotificationWireCodec.deriveKey(
                "FD-SLA-REPLAY-TEST", TENANT_A, CLOCK_A, "BREACH");
        UUID firstCorrelation = UUID.fromString("70000000-0000-0000-0000-000000000001");
        UUID replayCorrelation = UUID.fromString("70000000-0000-0000-0000-000000000002");
        TicketNotificationIntent.Intent original = TicketNotificationIntent.create(
                TENANT_A, RECIPIENT_A, TicketNotificationIntent.SLA_BREACH,
                sourceEffectKey, "SLA_CLOCK", CLOCK_A, "SLA breached",
                "The SLA clock breached.", firstCorrelation, null, NOW);
        TicketNotificationIntent.Intent replay = TicketNotificationIntent.create(
                TENANT_A, RECIPIENT_A, TicketNotificationIntent.SLA_BREACH,
                sourceEffectKey, "SLA_CLOCK", CLOCK_A, "SLA breached",
                "The SLA clock breached.", replayCorrelation,
                UUID.fromString("70000000-0000-0000-0000-000000000003"), NOW.plusSeconds(1));
        TicketNotificationIntent.Intent drift = TicketNotificationIntent.create(
                TENANT_A, RECIPIENT_A, TicketNotificationIntent.SLA_BREACH,
                sourceEffectKey, "SLA_CLOCK", CLOCK_A, "SLA breached",
                "Different payload must not converge.", replayCorrelation, null, NOW.plusSeconds(2));
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        TenantContext context = TenantContext.ofPersistedTask(TENANT_A,
                ActorRef.service("flowdesk-ticket-sla-test"), firstCorrelation);

        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            transactions.executeWithoutResult(tx -> notificationOutboxRepository.insertIntent(TENANT_A, original));
            transactions.executeWithoutResult(tx -> notificationOutboxRepository.insertIntent(TENANT_A, replay));
            assertThatThrownBy(() -> transactions.executeWithoutResult(
                    tx -> notificationOutboxRepository.insertIntent(TENANT_A, drift)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Conflicting Ticket Notification outbox identity");
        }

        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE tenant_id=?", TENANT_A)).isEqualTo(1);
        UUID persistedCorrelation = jdbc.queryForObject("""
                SELECT correlation_id FROM outbox_event WHERE tenant_id=? AND outbox_id=?
                """, (rs, row) -> TicketRepository.Uuids.toUuid(rs.getBytes(1)),
                TicketRepository.Uuids.toBytes(TENANT_A),
                TicketRepository.Uuids.toBytes(original.outboxId()));
        assertThat(persistedCorrelation).isEqualTo(firstCorrelation);
    }

    private static TicketSlaScanner.ScanResult scan(TicketSlaScanner scanner, CountDownLatch ready,
                                                     CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent scanner start was not released");
        }
        return scanner.scanOnce();
    }

    private void seedTicketAndClock(UUID tenantId, UUID ticketId, UUID clockId, Instant deadline) {
        Instant started = deadline.minusSeconds(60);
        jdbc.update("""
                INSERT INTO ticket
                  (tenant_id,ticket_id,ticket_no,creation_intent_key,origin_type,catalog_item_id,
                   requester_user_id,previous_attempt_ticket_id,subject,description,status,
                   assignee_user_id,workflow_definition_id,workflow_version_id,workflow_version_no,
                   first_processable_at,resolved_at,closed_at,last_reopened_at,reopen_count,terminal_at,
                   lock_version,created_at,created_by_type,created_by_id,updated_at,updated_by_type,updated_by_id)
                VALUES (?,?,?,?, 'MANUAL',NULL,?,NULL,'SLA race','scheduler concurrency test',
                        'PENDING_ASSIGNMENT',NULL,NULL,NULL,NULL,?,NULL,NULL,NULL,0,NULL,
                        0,?,'SERVICE',NULL,?,'SERVICE',NULL)
                """, TicketRepository.Uuids.toBytes(tenantId), TicketRepository.Uuids.toBytes(ticketId),
                "FD-SLA-" + ticketId.toString().substring(0, 8),
                NotificationWireCodec.deriveKey("FD-SLA-TEST-TICKET", tenantId, ticketId),
                TicketRepository.Uuids.toBytes(RECIPIENT_A), Timestamp.from(started), Timestamp.from(started),
                Timestamp.from(started));
        jdbc.update("""
                INSERT INTO sla_clock
                  (tenant_id,sla_clock_id,ticket_id,sla_policy_id,clock_type,status,started_at,
                   deadline_at,reminder_due_at,policy_duration_seconds,policy_reminder_before_seconds,
                   milestone_at,breached_at,satisfied_at,lock_version,created_at,created_by_type,
                   created_by_id,updated_at,updated_by_type,updated_by_id)
                VALUES (?,?,?,?, 'RESOLUTION','RUNNING',?,?,?,60,10,NULL,NULL,NULL,0,?,'SERVICE',NULL,?,'SERVICE',NULL)
                """, TicketRepository.Uuids.toBytes(tenantId), TicketRepository.Uuids.toBytes(clockId),
                TicketRepository.Uuids.toBytes(ticketId), TicketRepository.Uuids.toBytes(POLICY_A),
                Timestamp.from(started), Timestamp.from(deadline), Timestamp.from(deadline.minusSeconds(10)),
                Timestamp.from(started), Timestamp.from(started));
    }

    private void seedQualifiedTarget(UUID tenantId, UUID recipient) {
        Instant checked = NOW.minusSeconds(10);
        Instant validUntil = checked.plus(properties.getQualificationMaxAge());
        jdbc.update("""
                INSERT INTO sla_escalation_target
                  (tenant_id,target_id,purpose,recipient_user_id,status,effective_at,
                   qualification_version,qualification_checked_at,qualification_valid_until,
                   qualification_state,qualification_reason_code,lock_version,created_at,
                   created_by_type,created_by_id,updated_at,updated_by_type,updated_by_id)
                VALUES (?,?,'SLA',?,'ACTIVE',?,1,?,?,'ELIGIBLE','ELIGIBLE',0,?,'SERVICE',NULL,?,'SERVICE',NULL)
                """, TicketRepository.Uuids.toBytes(tenantId), TicketRepository.Uuids.toBytes(TARGET_A),
                TicketRepository.Uuids.toBytes(recipient), Timestamp.from(NOW.minusSeconds(1)),
                Timestamp.from(checked), Timestamp.from(validUntil), Timestamp.from(checked), Timestamp.from(checked));
        jdbc.update("""
                INSERT INTO sla_qualification_snapshot
                  (tenant_id,target_id,recipient_user_id,purpose,qualification_version,checked_at,
                   valid_until,eligible,reason_code,created_at,created_by_type,created_by_id)
                VALUES (?,?,?,'SLA',1,?,?,TRUE,'ELIGIBLE',?,'SERVICE',NULL)
                """, TicketRepository.Uuids.toBytes(tenantId), TicketRepository.Uuids.toBytes(TARGET_A),
                TicketRepository.Uuids.toBytes(recipient), Timestamp.from(checked), Timestamp.from(validUntil),
                Timestamp.from(checked));
    }

    private int count(String sql, UUID... values) {
        Object[] args = java.util.Arrays.stream(values).map(TicketRepository.Uuids::toBytes).toArray();
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static String readResource(String name) {
        try (InputStream stream = SlaConcurrencyIntegrationTest.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) {
                throw new IllegalStateException("Missing test resource: " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedTicketClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    private static final class RecordingIndex implements SlaDueIndex {
        private final ConcurrentMap<String, Candidate> values = new ConcurrentHashMap<>();

        @Override
        public void upsert(UUID tenantId, UUID slaClockId, String stage, Instant dueAt) {
            values.put(key(tenantId, slaClockId, stage), new Candidate(tenantId, slaClockId, stage,
                    dueAt.toEpochMilli()));
        }

        @Override
        public void remove(UUID tenantId, UUID slaClockId, String stage) {
            values.remove(key(tenantId, slaClockId, stage));
        }

        @Override
        public List<Candidate> due(UUID tenantId, Instant now, int limit) {
            return values.values().stream()
                    .filter(candidate -> tenantId.equals(candidate.tenantId())
                            && candidate.score() <= now.toEpochMilli())
                    .sorted(Comparator.comparingDouble(Candidate::score)
                            .thenComparing(candidate -> candidate.slaClockId().toString())
                            .thenComparing(Candidate::stage))
                    .limit(limit)
                    .toList();
        }

        @Override
        public void clear(UUID tenantId) {
            values.entrySet().removeIf(entry -> tenantId.equals(entry.getValue().tenantId()));
        }

        @Override
        public boolean available() {
            return true;
        }

        private static String key(UUID tenantId, UUID clockId, String stage) {
            return tenantId + "|" + clockId + "|" + stage;
        }
    }

    private static final class UnavailableIndex implements SlaDueIndex {
        @Override public void upsert(UUID tenantId, UUID slaClockId, String stage, Instant dueAt) { }
        @Override public void remove(UUID tenantId, UUID slaClockId, String stage) { }
        @Override public List<Candidate> due(UUID tenantId, Instant now, int limit) { return List.of(); }
        @Override public void clear(UUID tenantId) { }
        @Override public boolean available() { return false; }
    }
}
