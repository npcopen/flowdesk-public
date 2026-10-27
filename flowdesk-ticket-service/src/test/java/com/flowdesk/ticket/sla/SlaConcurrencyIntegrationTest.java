package com.flowdesk.ticket.sla;

import com.flowdesk.ticket.mapper.*;
import com.flowdesk.ticket.model.*;
import com.flowdesk.ticket.persistence.Uuids;

import com.flowdesk.common.notification.NotificationWireCodec;
import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.common.tenant.TenantContextMismatchException;
import com.flowdesk.ticket.notification.TicketNotificationIntent;
import com.flowdesk.ticket.notification.TicketNotificationDispatchService;


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

/**
 * M8 SLA 扫描并发测试，验证两个扫描器竞争同一时钟时，MySQL 业务事实仍只有一次有效写入。
 * 同时覆盖租户隔离、Redis 降级、资格刷新、通知补偿和重建租约围栏，防止竞争产生重复副作用。
 */
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
    private SlaPersistenceMapper repository;
    @Autowired private SlaClockService slaClockService;
    @Autowired
    private SlaTargetPersistenceMapper targetRepository;
    @Autowired
    private SlaRebuildLeaseService leaseRepository;
    @Autowired
    private TicketMapper ticketRepository;
    @Autowired
    private TicketAuditMapper ticketAuditMapper;
    @Autowired
    private TicketSlaService slaService;
    @Autowired
    private TicketSlaProperties properties;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private com.flowdesk.ticket.notification.TicketNotificationOutboxWriter notificationOutboxWriter;

    /**
     * 注册隔离测试数据库、密钥和功能开关，使场景在可控环境运行。
     */
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

    /**
     * 清理控制字符与多余空白，并限制错误摘要长度。
     */
    @BeforeEach
    void clean() {
        for (String table : List.of("sla_trigger_recipient", "sla_qualification_snapshot",
                "sla_escalation_target", "sla_index_rebuild_lease", "sla_trigger", "sla_clock",
                "ticket_timeline_entry", "audit_record", "outbox_event", "ticket")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    /**
     * 验证两个扫描器竞争同一时钟不重复业务效果，也不跨租户。
     */
    @Test
    void twoScannersRaceOneDueClockWithoutDuplicatingEffectsOrCrossingTenants() throws Exception {
        Instant deadline = NOW.minusSeconds(1);
        seedTicketAndClock(TENANT_A, TICKET_A, CLOCK_A, deadline);
        seedQualifiedTarget(TENANT_A, RECIPIENT_A);
        seedTicketAndClock(TENANT_B, TICKET_B, CLOCK_B, NOW.plusSeconds(60));

        RecordingIndex index = new RecordingIndex();
        index.upsert(TENANT_A, CLOCK_A, "BREACH", deadline);
        TicketSlaScanner first = new TicketSlaScanner(repository, leaseRepository, slaService, index, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        TicketSlaScanner second = new TicketSlaScanner(repository, leaseRepository, slaService, index, properties,
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
                String.class, Uuids.toBytes(TENANT_A), Uuids.toBytes(CLOCK_A)))
                .isEqualTo("BREACHED");
        assertThat(index.due(TENANT_A, NOW, 10)).isEmpty();
    }

    /**
     * 验证 Redis 不可用时仍通过有界数据库扫描触发 SLA。
     */
    @Test
    void unavailableRedisStillTriggersThroughTheBoundedMysqlSafetySweep() {
        seedTicketAndClock(TENANT_A, TICKET_A, CLOCK_A, NOW.minusSeconds(1));
        seedQualifiedTarget(TENANT_A, RECIPIENT_A);

        TicketSlaScanner scanner = new TicketSlaScanner(repository, leaseRepository, slaService, new UnavailableIndex(),
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

    /**
     * 验证资格刷新原因能提交，并满足成功审计契约。
     */
    @Test
    void qualificationRefreshCommitsReasonWithoutViolatingSuccessfulAuditContract() {
        seedQualifiedTarget(TENANT_A, RECIPIENT_A);
        SlaTargetQualificationService qualification = new com.flowdesk.ticket.sla.impl.SlaTargetQualificationServiceImpl(
                targetRepository, ticketAuditMapper,
                (tenantId, userId) -> new com.flowdesk.ticket.iam.SlaTargetQualificationChecker.Qualification(
                        userId, true, "ELIGIBLE", 2L, NOW),
                properties, transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));
        TenantContext context = TenantContext.ofPersistedTask(TENANT_A,
                ActorRef.service("flowdesk-ticket-sla-test"), UUID.randomUUID());

        com.flowdesk.ticket.model.sla.RefreshResult result;
        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            result = qualification.refreshTenant(TENANT_A);
        }

        assertThat(result.eligible()).isTrue();
        assertThat(result.code()).isEqualTo("ELIGIBLE");
        assertThat(jdbc.queryForObject("""
                SELECT qualification_version FROM sla_escalation_target
                WHERE tenant_id=? AND target_id=?
                """, Long.class, Uuids.toBytes(TENANT_A),
                Uuids.toBytes(TARGET_A))).isEqualTo(2L);
        assertThat(jdbc.queryForMap("""
                SELECT outcome,reason,error_code FROM audit_record
                WHERE tenant_id=? AND action_code='SLA_TARGET_QUALIFICATION_REFRESHED'
                """, Uuids.toBytes(TENANT_A)))
                .containsEntry("outcome", "SUCCEEDED")
                .containsEntry("reason", "SLA qualification refresh result: ELIGIBLE")
                .containsEntry("error_code", null);
    }

    /**
     * 验证资格过期时跳过通知，同时保留超时事实。
     */
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
                Uuids.toBytes(TENANT_A), Uuids.toBytes(TARGET_A));
        jdbc.update("""
                UPDATE sla_qualification_snapshot
                SET checked_at=?,valid_until=?
                WHERE tenant_id=? AND target_id=?
                """, Timestamp.from(expiredCheckedAt), Timestamp.from(expiredValidUntil),
                Uuids.toBytes(TENANT_A), Uuids.toBytes(TARGET_A));

        TicketSlaScanner scanner = new TicketSlaScanner(repository, leaseRepository, slaService, new UnavailableIndex(),
                properties, Clock.fixed(NOW, ZoneOffset.UTC));
        TicketSlaScanner.ScanResult result = scanner.scanOnce();

        assertThat(result.errors()).isZero();
        assertThat(result.triggered()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM sla_clock WHERE tenant_id=? AND sla_clock_id=?",
                String.class, Uuids.toBytes(TENANT_A),
                Uuids.toBytes(CLOCK_A))).isEqualTo("BREACHED");
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE tenant_id=?", TENANT_A)).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT outcome,reason,error_code FROM audit_record
                WHERE tenant_id=? AND action_code='SLA_BREACH_NOTIFICATION_SKIPPED'
                """, Uuids.toBytes(TENANT_A)))
                .containsEntry("outcome", "SUCCEEDED")
                .containsEntry("reason", "Notification skipped by configuration: SLA_TARGET_QUALIFICATION_INVALID")
                .containsEntry("error_code", null);
    }

    /**
     * 验证资格恢复后仅补发一次终态超时通知。
     */
    @Test
    void qualificationRecoveryRetriesATerminalBreachExactlyOnce() {
        expiredQualificationSkipsNotificationWithoutRollingBackTheBreach();
        Instant checked = NOW.minusSeconds(1), valid = checked.plus(properties.getQualificationMaxAge());
        jdbc.update("UPDATE sla_escalation_target SET qualification_checked_at=?,qualification_valid_until=?", Timestamp.from(checked), Timestamp.from(valid));
        jdbc.update("UPDATE sla_qualification_snapshot SET checked_at=?,valid_until=?", Timestamp.from(checked), Timestamp.from(valid));
        var scanner = new TicketSlaScanner(repository, leaseRepository, slaService, new UnavailableIndex(), properties,
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        assertThat(scanner.scanOnce().errors()).isZero();
        int notifications = count("SELECT COUNT(*) FROM outbox_event WHERE tenant_id=?", TENANT_A);
        assertThat(notifications).isPositive();
        assertThat(count("SELECT COUNT(*) FROM sla_trigger WHERE tenant_id=?", TENANT_A)).isEqualTo(1);
        assertThat(scanner.scanOnce().errors()).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE tenant_id=?", TENANT_A)).isEqualTo(notifications);
        assertThat(jdbc.queryForObject("SELECT status FROM sla_clock", String.class)).isEqualTo("BREACHED");
    }

    /**
     * 验证陈旧提醒候选记录错过原因，并满足审计约束。
     */
    @Test
    void staleRedisReminderRecordsMissWithoutViolatingSuccessfulAuditContract() {
        Instant deadline = NOW.minusSeconds(1);
        seedTicketAndClock(TENANT_A, TICKET_A, CLOCK_A, deadline);
        seedQualifiedTarget(TENANT_A, RECIPIENT_A);
        RecordingIndex index = new RecordingIndex();
        index.upsert(TENANT_A, CLOCK_A, "REMINDER", deadline.minusSeconds(10));
        index.upsert(TENANT_A, CLOCK_A, "BREACH", deadline);

        TicketSlaScanner scanner = new TicketSlaScanner(repository, leaseRepository, slaService, index,
                properties, Clock.fixed(NOW, ZoneOffset.UTC));
        TicketSlaScanner.ScanResult result = scanner.scanOnce();

        assertThat(result.errors()).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT outcome,reason,error_code FROM audit_record
                WHERE tenant_id=? AND action_code='SLA_REMINDER_MISSED_AFTER_DEADLINE'
                """, Uuids.toBytes(TENANT_A)))
                .containsEntry("outcome", "SUCCEEDED")
                .containsEntry("reason", "Reminder window closed before the scanner observed it: REMINDER_MISSED_AFTER_DEADLINE")
                .containsEntry("error_code", null);
    }

    /**
     * 验证通知重放保留首次链路，拒绝载荷漂移。
     */
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
            transactions.executeWithoutResult(tx -> notificationOutboxWriter.write(TENANT_A, original));
            transactions.executeWithoutResult(tx -> notificationOutboxWriter.write(TENANT_A, replay));
            assertThatThrownBy(() -> transactions.executeWithoutResult(
                    tx -> notificationOutboxWriter.write(TENANT_A, drift)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Conflicting Ticket Notification outbox identity");
        }

        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE tenant_id=?", TENANT_A)).isEqualTo(1);
        UUID persistedCorrelation = jdbc.queryForObject("""
                SELECT correlation_id FROM outbox_event WHERE tenant_id=? AND outbox_id=?
                """, (rs, row) -> Uuids.toUuid(rs.getBytes(1)),
                Uuids.toBytes(TENANT_A),
                Uuids.toBytes(original.outboxId()));
        assertThat(persistedCorrelation).isEqualTo(firstCorrelation);
    }

    /**
     * 验证重建保留截止边界，并隔离被替换的旧所有者。
     */
    @Test
    void rebuildLeasePreservesTheDeadlineBoundaryAndFencesTheReplacedOwner() {
        UUID firstRun = UUID.randomUUID();
        UUID nextRun = UUID.randomUUID();
        Instant deadline = NOW.plusSeconds(30);
        Instant takeoverAt = deadline.plusNanos(1_000);
        Instant nextDeadline = takeoverAt.plusSeconds(30);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        var context = TenantContext.ofPersistedTask(TENANT_A,
                ActorRef.service("flowdesk-ticket-sla-test"), UUID.randomUUID());
        try (var ignored = TenantContextScope.open(context)) {
            assertThatThrownBy(() -> leaseRepository.ensureSlaRebuildLease(TENANT_A, NOW))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> leaseRepository.findSlaRebuildLease(TENANT_B))
                    .isInstanceOf(TenantContextMismatchException.class);
            transactions.executeWithoutResult(tx -> {
                leaseRepository.ensureSlaRebuildLease(TENANT_A, NOW);
                assertThat(leaseRepository.claimSlaRebuildLease(TENANT_A, firstRun,
                        null, null, deadline, NOW)).isEqualTo(1);
            });
            long firstEpoch = leaseRepository.findSlaRebuildLease(TENANT_A).orElseThrow().fencingEpoch();

            transactions.executeWithoutResult(tx -> {
                assertThat(leaseRepository.claimSlaRebuildLease(TENANT_A, nextRun,
                        null, null, nextDeadline, deadline.minusNanos(1_000))).isZero();
                assertThat(leaseRepository.isSlaRebuildActive(TENANT_A, deadline)).isFalse();
                assertThat(leaseRepository.renewSlaRebuildLease(TENANT_A, firstRun, firstEpoch,
                        nextDeadline, deadline)).isZero();
                // The existing claim predicate is strict: equality is not a takeover.
                assertThat(leaseRepository.claimSlaRebuildLease(TENANT_A, nextRun,
                        null, null, nextDeadline, deadline)).isZero();
                assertThat(leaseRepository.claimSlaRebuildLease(TENANT_A, nextRun,
                        null, null, nextDeadline, takeoverAt)).isEqualTo(1);
            });
            var owner = leaseRepository.findSlaRebuildLease(TENANT_A).orElseThrow();
            assertThat(owner.fencingEpoch()).isEqualTo(firstEpoch + 1);
            assertThat(owner.runId()).isEqualTo(nextRun);

            transactions.executeWithoutResult(tx -> {
                assertThat(leaseRepository.renewSlaRebuildLease(TENANT_A, firstRun, firstEpoch,
                        nextDeadline, takeoverAt)).isZero();
                assertThat(leaseRepository.advanceSlaRebuildLease(TENANT_A, firstRun, firstEpoch,
                        "SWAPPING", nextDeadline, null, takeoverAt)).isZero();
                assertThat(leaseRepository.releaseSlaRebuildLease(TENANT_A, firstRun, firstEpoch,
                        "IDLE", null, takeoverAt)).isZero();
                assertThat(leaseRepository.advanceSlaRebuildLease(TENANT_A, nextRun, owner.fencingEpoch(),
                        "CATCHING_UP", nextDeadline, null, takeoverAt)).isEqualTo(1);
                assertThat(leaseRepository.releaseSlaRebuildLease(TENANT_A, nextRun, owner.fencingEpoch(),
                        "IDLE", null, takeoverAt)).isEqualTo(1);
            });
            assertThat(leaseRepository.findSlaRebuildLease(TENANT_A).orElseThrow().state()).isEqualTo("IDLE");
        }
    }

    /**
     * 验证拆分后的 SLA 持久化组件仍参与同一事务回滚。
     */
    @Test
    void splitSlaRepositoriesParticipateInTheSameRollback() {
        seedTicketAndClock(TENANT_A, TICKET_A, CLOCK_A, NOW.plusSeconds(60));
        seedQualifiedTarget(TENANT_A, RECIPIENT_A);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        var context = TenantContext.ofPersistedTask(TENANT_A,
                ActorRef.service("flowdesk-ticket-sla-test"), UUID.randomUUID());
        try (var ignored = TenantContextScope.open(context)) {
            assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
                assertThat(slaClockService.updateSlaClockSatisfiedOnTime(TENANT_A, CLOCK_A, 0, NOW)).isEqualTo(1);
                assertThat(targetRepository.deactivateSlaEscalationTargetUpdate(Timestamp.from(NOW), "SERVICE", null, Uuids.toBytes(TENANT_A), Uuids.toBytes(TARGET_A), 0)).isEqualTo(1);
                leaseRepository.ensureSlaRebuildLease(TENANT_A, NOW);
                throw new IllegalStateException("Abort all SLA writes");
            })).isInstanceOf(IllegalStateException.class).hasMessage("Abort all SLA writes");

            assertThat(repository.findSlaClockByIdSelect(Uuids.toBytes(TENANT_A), Uuids.toBytes(CLOCK_A)).stream().findFirst().orElseThrow().status()).isEqualTo("RUNNING");
            var target = targetRepository.findActiveSlaEscalationTargetSelect(Uuids.toBytes(TENANT_A), false).stream().findFirst().orElseThrow();
            assertThat(target.lockVersion()).isZero();
            assertThat(leaseRepository.findSlaRebuildLease(TENANT_A)).isEmpty();
        }
    }

    /**
     * 在可控时钟下执行一轮 SLA 扫描，供核验候选与阶段处理结果。
     */
    private static TicketSlaScanner.ScanResult scan(TicketSlaScanner scanner, CountDownLatch ready,
                                                     CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent scanner start was not released");
        }
        return scanner.scanOnce();
    }

    /**
     * 保存本场景前置业务事实，使用参数控制租户、身份、状态及时间边界。
     */
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
                """, Uuids.toBytes(tenantId), Uuids.toBytes(ticketId),
                "FD-SLA-" + ticketId.toString().substring(0, 8),
                NotificationWireCodec.deriveKey("FD-SLA-TEST-TICKET", tenantId, ticketId),
                Uuids.toBytes(RECIPIENT_A), Timestamp.from(started), Timestamp.from(started),
                Timestamp.from(started));
        jdbc.update("""
                INSERT INTO sla_clock
                  (tenant_id,sla_clock_id,ticket_id,sla_policy_id,clock_type,status,started_at,
                   deadline_at,reminder_due_at,policy_duration_seconds,policy_reminder_before_seconds,
                   milestone_at,breached_at,satisfied_at,lock_version,created_at,created_by_type,
                   created_by_id,updated_at,updated_by_type,updated_by_id)
                VALUES (?,?,?,?, 'RESOLUTION','RUNNING',?,?,?,60,10,NULL,NULL,NULL,0,?,'SERVICE',NULL,?,'SERVICE',NULL)
                """, Uuids.toBytes(tenantId), Uuids.toBytes(clockId),
                Uuids.toBytes(ticketId), Uuids.toBytes(POLICY_A),
                Timestamp.from(started), Timestamp.from(deadline), Timestamp.from(deadline.minusSeconds(10)),
                Timestamp.from(started), Timestamp.from(started));
    }

    /**
     * 保存本场景前置业务事实，使用参数控制租户、身份、状态及时间边界。
     */
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
                """, Uuids.toBytes(tenantId), Uuids.toBytes(TARGET_A),
                Uuids.toBytes(recipient), Timestamp.from(NOW.minusSeconds(1)),
                Timestamp.from(checked), Timestamp.from(validUntil), Timestamp.from(checked), Timestamp.from(checked));
        jdbc.update("""
                INSERT INTO sla_qualification_snapshot
                  (tenant_id,target_id,recipient_user_id,purpose,qualification_version,checked_at,
                   valid_until,eligible,reason_code,created_at,created_by_type,created_by_id)
                VALUES (?,?,?,'SLA',1,?,?,TRUE,'ELIGIBLE',?,'SERVICE',NULL)
                """, Uuids.toBytes(tenantId), Uuids.toBytes(TARGET_A),
                Uuids.toBytes(recipient), Timestamp.from(checked), Timestamp.from(validUntil),
                Timestamp.from(checked));
    }

    /**
     * 统计指定表中的测试记录，供断言效果数量和幂等性。
     */
    private int count(String sql, UUID... values) {
        Object[] args = java.util.Arrays.stream(values).map(Uuids::toBytes).toArray();
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    /**
     * 读取测试密钥或契约资源，缺失时立即失败。
     */
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

    /**
     * 用于该测试的内部替身，提供可控制的时钟、调度或依赖行为。
     */
    @TestConfiguration
    static class FixedClockConfiguration {
        /**
         * 装配SLA的 fixedTicketClock 组件，将配置与依赖交由 Spring 管理。
         */
        @Bean
        @Primary
        Clock fixedTicketClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    /**
     * 用于该测试的内部替身，提供可控制的时钟、调度或依赖行为。
     */
    private static final class RecordingIndex implements SlaDueIndex {
        private final ConcurrentMap<String, Candidate> values = new ConcurrentHashMap<>();

        /**
         * 写入或刷新到期候选，使扫描器按时间定位 SLA 阶段。
         */
        @Override
        public void upsert(UUID tenantId, UUID slaClockId, String stage, Instant dueAt) {
            values.put(key(tenantId, slaClockId, stage), new Candidate(tenantId, slaClockId, stage,
                    dueAt.toEpochMilli()));
        }

        /**
         * 移除已失效候选，避免已结算或已触发阶段继续被扫描。
         */
        @Override
        public void remove(UUID tenantId, UUID slaClockId, String stage) {
            values.remove(key(tenantId, slaClockId, stage));
        }

        /**
         * 按租户、截止时间与数量上限读取到期候选，供后台有界处理。
         */
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

        /**
         * 清理指定租户索引，供后续从数据库事实重新建立。
         */
        @Override
        public void clear(UUID tenantId) {
            values.entrySet().removeIf(entry -> tenantId.equals(entry.getValue().tenantId()));
        }

        /**
         * 内存索引替身始终可用，用于测试正常索引扫描路径。
         */
        @Override
        public boolean available() {
            return true;
        }

        /**
         * 组合租户、时钟和阶段生成内存索引键，保持测试候选隔离。
         */
        private static String key(UUID tenantId, UUID clockId, String stage) {
            return tenantId + "|" + clockId + "|" + stage;
        }
    }

    /**
     * 用于该测试的内部替身，提供可控制的时钟、调度或依赖行为。
     */
    private static final class UnavailableIndex implements SlaDueIndex {
        /**
         * 模拟不可用索引，忽略写入以验证数据库降级路径。
         */
        @Override public void upsert(UUID tenantId, UUID slaClockId, String stage, Instant dueAt) { }
        /**
         * 模拟不可用索引，忽略候选删除。
         */
        @Override public void remove(UUID tenantId, UUID slaClockId, String stage) { }
        /**
         * 模拟索引无可用候选，返回空集合。
         */
        @Override public List<Candidate> due(UUID tenantId, Instant now, int limit) { return List.of(); }
        /**
         * 模拟不可用索引，不执行任何外部清理。
         */
        @Override public void clear(UUID tenantId) { }
        /**
         * 始终返回 false，触发被测扫描器的数据库安全扫描。
         */
        @Override public boolean available() { return false; }
    }
}
