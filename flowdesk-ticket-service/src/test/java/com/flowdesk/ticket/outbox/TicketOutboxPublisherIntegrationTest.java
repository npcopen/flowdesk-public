package com.flowdesk.ticket.outbox;

import com.flowdesk.ticket.mapper.*;
import com.flowdesk.ticket.model.*;
import com.flowdesk.ticket.persistence.Uuids;

import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.common.tenant.TenantContextSource;
import com.flowdesk.ticket.event.TicketEventContract;
import com.flowdesk.ticket.persistence.Uuids;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单模块回归测试，覆盖该组件的正常流程、边界条件与关键一致性约束。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:ticket-outbox;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:schema-test.sql",
                "flowdesk.ticket.outbox.publisher.enabled=false",
                "flowdesk.security.jwt.public-key-location=classpath:test-jwt-public-key.pem",
                "flowdesk.security.cursor-token.hmac-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "FLOWDESK_TICKET_DB_PASSWORD="
        })
class TicketOutboxPublisherIntegrationTest {

    private static final String FIXTURE =
            "contracts/flowdesk-workflow-start-v1-wire.properties";
    private static final Properties WIRE = loadFixture();
    private static final UUID TENANT_ID = uuid("fd_tenant_id");
    private static final UUID TICKET_ID = uuid("fd_aggregate_id");
    private static final UUID MESSAGE_ID = uuid("fd_message_id");
    private static final UUID CORRELATION_ID = uuid("fd_correlation_id");
    private static final Instant CREATED_AT = Instant.parse(required("fd_occurred_at"));
    private static final byte[] BODY = Base64.getDecoder().decode(required("body.base64"));
    private static final byte[] BODY_HASH = HexFormat.of().parseHex(required("fd_body_sha256"));
    private static final byte[] BUSINESS_INTENT_KEY =
            HexFormat.of().parseHex(required("fd_business_intent_key"));

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private WorkflowStartDispatchService repository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ApplicationContext applicationContext;

    /**
     * 清理控制字符与多余空白，并限制错误摘要长度。
     */
    @BeforeEach
    void clean() {
        TenantContextHolder.clear();
        jdbc.update("DELETE FROM message_recovery");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM workflow_start_link");
    }

    /**
     * 清理测试线程的租户上下文，避免后续场景继承前一租户。
     */
    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
    }

    /**
     * 验证生产 MQ 适配器和轮询器默认关闭。
     */
    @Test
    void productionMqAdapterAndPollingWorkerAreDisabledByDefault() {
        assertThat(applicationContext.getBeansOfType(RocketMqTicketOutboxTransport.class))
                .isEmpty();
        assertThat(applicationContext.getBeansOfType(TicketOutboxPublisher.class))
                .isEmpty();
        assertThat(applicationContext.getBeansOfType(TicketOutboxPollingWorker.class))
                .isEmpty();
    }

    /**
     * 验证候选扫描数量有界，并遵守到期时间及领取租约。
     */
    @Test
    void candidateScanIsBoundedAndHonorsDueTimeAndLease() {
        Instant now = CREATED_AT.plusSeconds(100);
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 49; i++) {
            UUID outboxId = UUID.randomUUID();
            expected.add(outboxId);
            insertOutbox(outboxId, UUID.randomUUID(), "PENDING", 0,
                    CREATED_AT.plusSeconds(21), null, null, 0, CREATED_AT, null);
        }
        UUID expiredLease = UUID.randomUUID();
        expected.add(expiredLease);
        insertOutbox(expiredLease, UUID.randomUUID(), "PUBLISHING", 1,
                CREATED_AT, "old-claim", CREATED_AT.plusSeconds(20), 1, CREATED_AT, null);

        UUID fiftyFirstDue = UUID.randomUUID();
        insertOutbox(fiftyFirstDue, UUID.randomUUID(), "PENDING", 0,
                CREATED_AT.plusSeconds(22), null, null, 0, CREATED_AT, null);
        UUID future = UUID.randomUUID();
        insertOutbox(future, UUID.randomUUID(), "PENDING", 0,
                now.plusSeconds(1), null, null, 0, CREATED_AT, null);
        UUID activeLease = UUID.randomUUID();
        insertOutbox(activeLease, UUID.randomUUID(), "PUBLISHING", 1,
                CREATED_AT, "active-claim", now.minusSeconds(29), 1, CREATED_AT, null);
        UUID published = UUID.randomUUID();
        insertOutbox(published, UUID.randomUUID(), "PUBLISHED", 1,
                CREATED_AT, null, null, 2, CREATED_AT, now);
        UUID recovery = UUID.randomUUID();
        insertOutbox(recovery, UUID.randomUUID(), "RECOVERY_REQUIRED", 8,
                CREATED_AT, null, null, 9, CREATED_AT, null);

        List<com.flowdesk.ticket.model.outbox.Candidate> candidates = repository.findCandidates(
                now, now.minusSeconds(30), 50);

        assertThat(candidates).hasSize(50);
        assertThat(candidates).extracting(com.flowdesk.ticket.model.outbox.Candidate::outboxId)
                .containsExactlyInAnyOrderElementsOf(expected)
                .doesNotContain(fiftyFirstDue, future, activeLease, published, recovery);
        assertThat(candidates).filteredOn(candidate -> "PUBLISHING".equals(candidate.status()))
                .singleElement().extracting(com.flowdesk.ticket.model.outbox.Candidate::outboxId)
                .isEqualTo(expiredLease);
    }

    /**
     * 验证并发领取只有一个成功者，且仅消耗一次尝试。
     */
    @Test
    void concurrentClaimHasOneWinnerAndConsumesOneAttempt() throws Exception {
        UUID outboxId = UUID.randomUUID();
        insertOutbox(outboxId, UUID.randomUUID(), "PENDING", 0,
                CREATED_AT, null, null, 0, CREATED_AT, null);
        Instant now = CREATED_AT.plusSeconds(1);
        com.flowdesk.ticket.model.outbox.Candidate candidate = repository.findCandidates(
                now, now.minusSeconds(30), 50).get(0);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<com.flowdesk.ticket.model.outbox.ClaimedOutbox>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    TenantContext context = TenantContext.ofPersistedTask(
                            TENANT_ID,
                            ActorRef.service(TicketEventContract.WorkflowStartRequested.PRODUCER_SERVICE),
                            CORRELATION_ID);
                    try (TenantContextScope ignored = TenantContextScope.open(context)) {
                        return new TransactionTemplate(transactionManager).execute(status ->
                                repository.claimForSend(candidate, UUID.randomUUID().toString(),
                                                now, now.minusSeconds(30))
                                        .orElse(null));
                    }
                }));
            }
            start.countDown();
            List<com.flowdesk.ticket.model.outbox.ClaimedOutbox> winners = new ArrayList<>();
            for (Future<com.flowdesk.ticket.model.outbox.ClaimedOutbox> future : futures) {
                com.flowdesk.ticket.model.outbox.ClaimedOutbox claimed = future.get(5, TimeUnit.SECONDS);
                if (claimed != null) {
                    winners.add(claimed);
                }
            }

            assertThat(winners).singleElement().satisfies(claimed -> {
                assertThat(claimed.attemptCount()).isEqualTo(1);
                assertThat(claimed.lockVersion()).isEqualTo(1);
                assertThat(claimed.claimToken()).isNotBlank().hasSize(36);
            });
            assertThat(jdbc.queryForObject("""
                    SELECT attempt_count FROM outbox_event
                    WHERE tenant_id = ? AND outbox_id = ?
                    """, Integer.class, Uuids.toBytes(TENANT_ID), Uuids.toBytes(outboxId)))
                    .isEqualTo(1);
        }
        finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(TenantContextHolder.currentOrNull()).isNull();
    }

    /**
     * 验证发送在领取事务外执行，成功消息字节严格遵循冻结协议。
     */
    @Test
    void sendOkPublishesExactFrozenWireOutsideTheClaimTransaction() {
        UUID outboxId = UUID.randomUUID();
        insertWorkflowStartLink();
        insertOutbox(outboxId, MESSAGE_ID, "PENDING", 0,
                CREATED_AT, null, null, 0, CREATED_AT, null);
        MutableClock clock = new MutableClock(CREATED_AT.plusSeconds(1));
        List<TicketOutboxMessage> sent = new ArrayList<>();
        TicketOutboxPublisher publisher = publisher(clock, message -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            TenantContext context = TenantContextHolder.current();
            assertThat(context.tenantId()).isEqualTo(TENANT_ID);
            assertThat(context.source()).isEqualTo(TenantContextSource.PERSISTED_TASK);
            assertThat(context.actor()).isEqualTo(new ActorRef.Service(
                    TicketEventContract.WorkflowStartRequested.PRODUCER_SERVICE));
            assertThat(context.correlationId()).isEqualTo(CORRELATION_ID);
            assertThat(jdbc.queryForObject("""
                    SELECT status FROM outbox_event
                    WHERE tenant_id = ? AND outbox_id = ?
                    """, String.class, Uuids.toBytes(TENANT_ID), Uuids.toBytes(outboxId)))
                    .isEqualTo("PUBLISHING");
            assertThat(jdbc.queryForObject("""
                    SELECT attempt_count FROM outbox_event
                    WHERE tenant_id = ? AND outbox_id = ?
                    """, Integer.class, Uuids.toBytes(TENANT_ID), Uuids.toBytes(outboxId)))
                    .isEqualTo(1);
            sent.add(message);
            return TicketOutboxSendResult.sendOk();
        });

        TicketOutboxPublisher.PublishRunResult result = publisher.publishOnce();

        assertThat(result).isEqualTo(new TicketOutboxPublisher.PublishRunResult(
                1, 1, 0, 1, 0, 0, 0, 0));
        assertThat(sent).singleElement().satisfies(message -> {
            assertThat(message.topic()).isEqualTo(required("topic"));
            assertThat(message.keys()).isEqualTo(required("keys"));
            assertThat(message.body()).containsExactly(BODY);
            assertThat(message.userProperties()).isEqualTo(fixtureEnvelopeProperties());
        });
        assertThat(TicketEventContract.WorkflowStartRequested.PRODUCER_GROUP)
                .isEqualTo("flowdesk_workflow_start_v1_ticket_pg");
        assertThat(TicketEventContract.WorkflowStartRequested.SEND_TIMEOUT)
                .isEqualTo(Duration.ofSeconds(5));
        assertThat(TicketEventContract.WorkflowStartRequested.SYNCHRONOUS_SEND_RETRIES).isZero();
        assertThat(TicketEventContract.WorkflowStartRequested.ASYNCHRONOUS_SEND_RETRIES).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT status FROM outbox_event WHERE tenant_id = ? AND outbox_id = ?
                """, String.class, Uuids.toBytes(TENANT_ID), Uuids.toBytes(outboxId)))
                .isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("""
                SELECT broker_confirmed_at FROM outbox_event WHERE tenant_id = ? AND outbox_id = ?
                """, Timestamp.class, Uuids.toBytes(TENANT_ID), Uuids.toBytes(outboxId)))
                .isNotNull();
        assertThat(jdbc.queryForObject("""
                SELECT locked_by FROM outbox_event WHERE tenant_id = ? AND outbox_id = ?
                """, String.class, Uuids.toBytes(TENANT_ID), Uuids.toBytes(outboxId)))
                .isNull();
        assertThat(jdbc.queryForObject("""
                SELECT status FROM workflow_start_link WHERE tenant_id = ? AND ticket_id = ?
                """, String.class, Uuids.toBytes(TENANT_ID), Uuids.toBytes(TICKET_ID)))
                .isEqualTo("REQUESTED");
        assertThat(TenantContextHolder.currentOrNull()).isNull();
    }

    /**
     * 验证八次失败遵循固定退避，耗尽后原子创建恢复事实。
     */
    @Test
    void failuresOneThroughEightUseFrozenBackoffThenCreateAtomicRecovery() {
        UUID outboxId = UUID.randomUUID();
        insertWorkflowStartLink();
        insertOutbox(outboxId, MESSAGE_ID, "PENDING", 0,
                CREATED_AT, null, null, 0, CREATED_AT, null);
        MutableClock clock = new MutableClock(CREATED_AT);
        List<TicketOutboxMessage> sent = new ArrayList<>();
        TicketOutboxPublisher publisher = publisher(clock, message -> {
            sent.add(message);
            return TicketOutboxSendResult.notConfirmed(
                    "BROKER_UNAVAILABLE", " broker\r\n unavailable " + Character.toString(0));
        });

        List<Duration> backoffs = TicketEventContract.WorkflowStartRequested.FAILURE_BACKOFFS;
        for (int attempt = 1; attempt <= backoffs.size(); attempt++) {
            TicketOutboxPublisher.PublishRunResult result = publisher.publishOnce();
            assertThat(result.rescheduled()).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT status FROM outbox_event WHERE tenant_id = ? AND outbox_id = ?
                    """, String.class, Uuids.toBytes(TENANT_ID), Uuids.toBytes(outboxId)))
                    .isEqualTo("PENDING");
            Instant expectedNext = clock.instant().plus(backoffs.get(attempt - 1));
            assertThat(jdbc.queryForObject("""
                    SELECT next_attempt_at FROM outbox_event WHERE tenant_id = ? AND outbox_id = ?
                    """, Timestamp.class, Uuids.toBytes(TENANT_ID), Uuids.toBytes(outboxId))
                    .toInstant()).isEqualTo(expectedNext);
            assertThat(jdbc.queryForObject("""
                    SELECT attempt_count FROM outbox_event WHERE tenant_id = ? AND outbox_id = ?
                    """, Integer.class, Uuids.toBytes(TENANT_ID), Uuids.toBytes(outboxId)))
                    .isEqualTo(attempt);
            clock.set(expectedNext);
        }

        TicketOutboxPublisher.PublishRunResult eighth = publisher.publishOnce();

        assertThat(eighth.recovered()).isEqualTo(1);
        assertThat(eighth.rescheduled()).isZero();
        assertThat(sent).hasSize(8);
        TicketOutboxMessage first = sent.get(0);
        assertThat(sent).allSatisfy(message -> {
            assertThat(message.topic()).isEqualTo(first.topic());
            assertThat(message.keys()).isEqualTo(first.keys());
            assertThat(message.body()).containsExactly(first.body());
            assertThat(message.userProperties()).isEqualTo(first.userProperties());
        });
        assertOutboxRecovered(outboxId);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM workflow_start_link WHERE tenant_id = ? AND ticket_id = ?
                """, String.class, Uuids.toBytes(TENANT_ID), Uuids.toBytes(TICKET_ID)))
                .isEqualTo("RECOVERY_REQUIRED");
        assertThat(jdbc.queryForObject("""
                SELECT retry_count FROM workflow_start_link WHERE tenant_id = ? AND ticket_id = ?
                """, Integer.class, Uuids.toBytes(TENANT_ID), Uuids.toBytes(TICKET_ID)))
                .isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                SELECT last_attempt_at FROM workflow_start_link WHERE tenant_id = ? AND ticket_id = ?
                """, Timestamp.class, Uuids.toBytes(TENANT_ID), Uuids.toBytes(TICKET_ID)))
                .isNotNull();
        assertRecovery(outboxId, "BROKER_UNAVAILABLE", "broker unavailable");

        clock.set(clock.instant().plus(Duration.ofDays(1)));
        assertThat(publisher.publishOnce().scanned()).isZero();
        assertThat(sent).hasSize(8);
        assertThat(TenantContextHolder.currentOrNull()).isNull();
    }

    /**
     * 验证过期租约可被接管，旧执行者无法覆盖新结算。
     */
    @Test
    void expiredLeaseCanBeReclaimedAndOldCompletionCannotWin() {
        UUID outboxId = UUID.randomUUID();
        insertWorkflowStartLink();
        insertOutbox(outboxId, MESSAGE_ID, "PENDING", 0,
                CREATED_AT, null, null, 0, CREATED_AT, null);
        Instant firstClaimAt = CREATED_AT.plusSeconds(1);
        com.flowdesk.ticket.model.outbox.Candidate candidate = repository.findCandidates(
                firstClaimAt, firstClaimAt.minusSeconds(30), 50).get(0);
        com.flowdesk.ticket.model.outbox.ClaimedOutbox oldClaim = inTenantContext(() ->
                new TransactionTemplate(transactionManager).execute(status ->
                        repository.claimForSend(candidate, "old-worker-claim",
                                        firstClaimAt, firstClaimAt.minusSeconds(30))
                                .orElseThrow()));

        Instant leaseExpiredAt = firstClaimAt.plusSeconds(30);
        Boolean lateSuccess = inTenantContext(() ->
                new TransactionTemplate(transactionManager).execute(status ->
                        repository.markPublished(oldClaim, leaseExpiredAt,
                                leaseExpiredAt.minusSeconds(30))));
        assertThat(lateSuccess).isFalse();

        MutableClock clock = new MutableClock(leaseExpiredAt);
        AtomicInteger sends = new AtomicInteger();
        TicketOutboxPublisher publisher = publisher(clock, message -> {
            sends.incrementAndGet();
            return TicketOutboxSendResult.sendOk();
        });
        TicketOutboxPublisher.PublishRunResult result = publisher.publishOnce();

        assertThat(result.published()).isEqualTo(1);
        assertThat(sends).hasValue(1);
        assertThat(jdbc.queryForObject("""
                SELECT attempt_count FROM outbox_event WHERE tenant_id = ? AND outbox_id = ?
                """, Integer.class, Uuids.toBytes(TENANT_ID), Uuids.toBytes(outboxId)))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM outbox_event WHERE tenant_id = ? AND outbox_id = ?
                """, String.class, Uuids.toBytes(TENANT_ID), Uuids.toBytes(outboxId)))
                .isEqualTo("PUBLISHED");
        Boolean oldFailure = inTenantContext(() ->
                new TransactionTemplate(transactionManager).execute(status ->
                        repository.markPending(oldClaim, clock.instant(),
                                clock.instant().minusSeconds(30),
                                clock.instant().plusSeconds(1), "BROKER_RESULT_UNKNOWN")));
        assertThat(oldFailure).isFalse();
    }

    /**
     * 验证第八次领取过期后直接恢复，不进行第九次发送。
     */
    @Test
    void expiredEighthClaimConvergesRecoveryWithoutNinthSend() {
        UUID outboxId = UUID.randomUUID();
        insertWorkflowStartLink();
        Instant now = CREATED_AT.plusSeconds(100);
        insertOutbox(outboxId, MESSAGE_ID, "PUBLISHING", 8,
                CREATED_AT, "crashed-eighth-claim", now.minusSeconds(31), 8,
                CREATED_AT, null);
        AtomicInteger sends = new AtomicInteger();
        TicketOutboxPublisher publisher = publisher(new MutableClock(now), message -> {
            sends.incrementAndGet();
            return TicketOutboxSendResult.sendOk();
        });

        TicketOutboxPublisher.PublishRunResult result = publisher.publishOnce();

        assertThat(result).isEqualTo(new TicketOutboxPublisher.PublishRunResult(
                1, 0, 1, 0, 0, 1, 0, 0));
        assertThat(sends).hasValue(0);
        assertOutboxRecovered(outboxId);
        assertRecovery(outboxId, TicketOutboxPublisher.UNKNOWN_RESULT, null);
        assertThat(jdbc.queryForObject("""
                SELECT retry_count FROM workflow_start_link WHERE tenant_id = ? AND ticket_id = ?
                """, Integer.class, Uuids.toBytes(TENANT_ID), Uuids.toBytes(TICKET_ID)))
                .isEqualTo(8);
    }

    /**
     * 装配测试发布器，注入可控传输结果、事务管理器和时钟。
     */
    private TicketOutboxPublisher publisher(Clock clock, TicketOutboxTransport transport) {
        return new TicketOutboxPublisher(repository, transport, transactionManager, clock);
    }

    /**
     * 在指定测试租户上下文执行动作，并在结束后清理作用域。
     */
    private <T> T inTenantContext(Supplier<T> work) {
        TenantContext context = TenantContext.ofPersistedTask(
                TENANT_ID,
                ActorRef.service(TicketEventContract.WorkflowStartRequested.PRODUCER_SERVICE),
                CORRELATION_ID);
        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            return work.get();
        }
    }

    /**
     * 保存测试启动关联，控制发件箱结算前的业务状态。
     */
    private void insertWorkflowStartLink() {
        jdbc.update("""
                INSERT INTO workflow_start_link
                  (tenant_id, ticket_id, start_intent_key, status, workflow_instance_id,
                   requested_at, established_at, recovery_required_at, recovery_reason_code,
                   retry_count, last_attempt_at, lock_version, created_at, created_by_type,
                   created_by_id, updated_at, updated_by_type, updated_by_id)
                VALUES (?, ?, ?, 'REQUESTED', NULL, ?, NULL, NULL, NULL,
                        0, NULL, 0, ?, 'SERVICE', NULL, ?, 'SERVICE', NULL)
                """, Uuids.toBytes(TENANT_ID), Uuids.toBytes(TICKET_ID), BUSINESS_INTENT_KEY,
                Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));
    }

    /**
     * 保存测试发件箱原文和状态，构造待发、耗尽或过期领取场景。
     */
    private void insertOutbox(UUID outboxId, UUID messageId, String status, int attemptCount,
                              Instant nextAttemptAt, String lockedBy, Instant lockedAt,
                              long lockVersion, Instant createdAt, Instant brokerConfirmedAt) {
        jdbc.update("""
                INSERT INTO outbox_event
                  (tenant_id, outbox_id, message_id, publication_key, business_intent_key,
                   aggregate_type, aggregate_id, contract_ref, contract_version, body_bytes,
                   body_sha256, correlation_id, causation_id, status, attempt_count,
                   next_attempt_at, locked_by, locked_at, last_error_code,
                   broker_confirmed_at, lock_version, created_at, created_by_type, created_by_id,
                   updated_at, updated_by_type, updated_by_id)
                VALUES (?, ?, ?, ?, ?, 'TICKET', ?, ?, 1, ?, ?, ?, NULL, ?, ?,
                        ?, ?, ?, NULL, ?, ?, ?, 'SERVICE', NULL, ?, 'SERVICE', NULL)
                """, Uuids.toBytes(TENANT_ID), Uuids.toBytes(outboxId), Uuids.toBytes(messageId),
                sha256(messageId.toString().getBytes(StandardCharsets.UTF_8)), BUSINESS_INTENT_KEY,
                Uuids.toBytes(TICKET_ID), TicketEventContract.WorkflowStartRequested.CONTRACT_REF,
                BODY, BODY_HASH, Uuids.toBytes(CORRELATION_ID), status, attemptCount,
                Timestamp.from(nextAttemptAt), lockedBy,
                lockedAt == null ? null : Timestamp.from(lockedAt),
                brokerConfirmedAt == null ? null : Timestamp.from(brokerConfirmedAt), lockVersion,
                Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    /**
     * 断言发件箱进入恢复状态，且其关联事实符合预期。
     */
    private void assertOutboxRecovered(UUID outboxId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT status, attempt_count, locked_by, locked_at, broker_confirmed_at,
                       message_id, publication_key, business_intent_key, body_bytes, body_sha256,
                       correlation_id, created_at
                FROM outbox_event WHERE tenant_id = ? AND outbox_id = ?
                """, Uuids.toBytes(TENANT_ID), Uuids.toBytes(outboxId));
        assertThat(row.get("status")).isEqualTo("RECOVERY_REQUIRED");
        assertThat(row.get("attempt_count")).isEqualTo(8);
        assertThat(row.get("locked_by")).isNull();
        assertThat(row.get("locked_at")).isNull();
        assertThat(row.get("broker_confirmed_at")).isNull();
        assertThat((byte[]) row.get("message_id")).containsExactly(Uuids.toBytes(MESSAGE_ID));
        assertThat((byte[]) row.get("publication_key")).containsExactly(
                sha256(MESSAGE_ID.toString().getBytes(StandardCharsets.UTF_8)));
        assertThat((byte[]) row.get("business_intent_key")).containsExactly(BUSINESS_INTENT_KEY);
        assertThat((byte[]) row.get("body_bytes")).containsExactly(BODY);
        assertThat((byte[]) row.get("body_sha256")).containsExactly(BODY_HASH);
        assertThat((byte[]) row.get("correlation_id")).containsExactly(Uuids.toBytes(CORRELATION_ID));
        assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(CREATED_AT);
    }

    /**
     * 核对恢复项状态、来源和数量，保证重复执行未产生多余记录。
     */
    private void assertRecovery(UUID outboxId, String errorCode, String errorSummary) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT direction, source_record_id, business_intent_key, state, attempt_count,
                       next_attempt_at, last_error_code, last_error_summary, dead_lettered_at
                FROM message_recovery WHERE tenant_id = ?
                """, Uuids.toBytes(TENANT_ID));
        assertThat(row.get("direction")).isEqualTo("OUTBOX");
        assertThat((byte[]) row.get("source_record_id")).containsExactly(Uuids.toBytes(outboxId));
        assertThat((byte[]) row.get("business_intent_key")).containsExactly(BUSINESS_INTENT_KEY);
        assertThat(row.get("state")).isEqualTo("OPEN");
        assertThat(row.get("attempt_count")).isEqualTo(8);
        assertThat(row.get("next_attempt_at")).isNull();
        assertThat(row.get("last_error_code")).isEqualTo(errorCode);
        assertThat(row.get("last_error_summary")).isEqualTo(errorSummary);
        assertThat(row.get("dead_lettered_at")).isNull();
    }

    /**
     * 读取夹具信封字段，供构造与冻结协议一致的消息。
     */
    private static Map<String, String> fixtureEnvelopeProperties() {
        Map<String, String> properties = new HashMap<>();
        for (String name : WIRE.stringPropertyNames()) {
            if (name.startsWith("fd_")) {
                properties.put(name, required(name));
            }
        }
        return properties;
    }

    /**
     * 读取冻结消息夹具，供编码与实际传输内容逐字段核验。
     */
    private static Properties loadFixture() {
        Properties properties = new Properties();
        try (InputStream input = TicketOutboxPublisherIntegrationTest.class
                .getClassLoader().getResourceAsStream(FIXTURE)) {
            if (input == null) {
                throw new IllegalStateException("Missing shared fixture " + FIXTURE);
            }
            properties.load(input);
            return properties;
        }
        catch (Exception ex) {
            throw new IllegalStateException("Unable to load shared fixture", ex);
        }
    }

    /**
     * 读取测试夹具必需字段，缺失时立即失败以避免不完整测试输入。
     */
    private static String required(String name) {
        String value = WIRE.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Fixture property is missing: " + name);
        }
        return value;
    }

    /**
     * 转换业务标识与规范 UUID 字节表示，遵循既有空值与格式校验约定。
     */
    private static UUID uuid(String name) {
        return UUID.fromString(required(name));
    }

    /**
     * 计算输入内容 SHA-256 摘要，供消息完整性和业务意图核验。
     */
    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 用于该测试的内部替身，提供可控制的时钟、调度或依赖行为。
     */
    private static final class MutableClock extends Clock {
        private Instant current;

        /**
         * 初始化工作流启动发布测试替身，保存当前场景使用的可控状态与依赖。
         */
        private MutableClock(Instant current) {
            this.current = current;
        }

        /**
         * 推进可变测试时钟，供精确控制重试到期和领取租约边界。
         */
        private void set(Instant current) {
            this.current = current;
        }

        /**
         * 返回测试时钟使用的时区。
         */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /**
         * 按测试时钟约定返回指定时区视图，保持可控时间不变。
         */
        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Test clock is fixed to UTC");
            }
            return this;
        }

        /**
         * 取得当前规范精度时间。
         */
        @Override
        public Instant instant() {
            return current;
        }
    }
}
