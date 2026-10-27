package com.flowdesk.workflow.start;

import com.flowdesk.workflow.service.start.*;
import com.flowdesk.workflow.service.start.impl.*;
import com.flowdesk.workflow.service.recovery.*;
import com.flowdesk.workflow.service.recovery.impl.*;
import com.flowdesk.workflow.model.recovery.WorkflowRecoveryModels;
import com.flowdesk.workflow.dto.*;
import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.ticket.event.TicketEventContract;
import com.flowdesk.ticket.event.WorkflowStartV1PayloadCodec;
import com.flowdesk.ticket.outbox.RocketMqTicketOutboxTransport;
import com.flowdesk.ticket.outbox.TicketOutboxMessage;
import com.flowdesk.ticket.outbox.TicketOutboxPublisher;
import com.flowdesk.ticket.outbox.TicketOutboxPublisherProperties;
import com.flowdesk.ticket.outbox.WorkflowStartDispatchService;
import com.flowdesk.ticket.outbox.impl.WorkflowStartDispatchServiceImpl;
import com.flowdesk.ticket.model.outbox.Candidate;
import com.flowdesk.ticket.model.outbox.ClaimedOutbox;
import com.flowdesk.ticket.outbox.TicketOutboxSendResult;
import com.flowdesk.ticket.outbox.TicketOutboxTransport;
import com.flowdesk.ticket.service.IdempotencyCodec;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.QueryResult;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.RPCHook;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.apache.rocketmq.remoting.protocol.admin.TopicOffset;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 里程碑五真实基础设施验收，覆盖消息重投、身份冲突、租约竞争、ACL 和数据库故障的最终一致结果。
 */
@EnabledIfEnvironmentVariable(named = "FLOWDESK_M5_REAL_INFRA", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(value = 20, unit = TimeUnit.MINUTES)
class Milestone5RealInfrastructureAcceptanceTest {

    static final List<String> FROZEN_SCENARIOS = List.of(
            "M5-F01", "M5-F02", "M5-F03", "M5-F04", "M5-F05", "M5-F06",
            "M5-F07", "M5-F08", "M5-F09", "M5-F10", "M5-F11", "M5-F12",
            "M5-F13", "M5-F14");

    private static final UUID REQUESTER = UUID.fromString(
            "7cf4974e-9441-5bd6-aa47-2a1abdeded41");
    private static final UUID APPROVER_1 = UUID.fromString(
            "33f0b408-5548-5f2b-9865-f1dd288c0493");
    private static final UUID APPROVER_2 = UUID.fromString(
            "fdecef4b-2a69-538e-9fa5-249048115d3d");
    private static final UUID ADMIN_USER = UUID.fromString(
            "601b6f4e-2d6d-59f1-a826-60ad58c334b5");
    private static final UUID DEMO_SLA = UUID.fromString(
            "8eee84ca-e2c3-5c9e-af49-87f3c171e1cc");
    private static final String MAIN_DLQ = "%DLQ%flowdesk_workflow_start_v1_workflow_cg";
    private static final String SECONDARY_DLQ =
            "%DLQ%flowdesk_workflow_start_v1_workflow_dlq_acceptor_cg";
    private static final String HOST_TOPIC = "flowdesk_host_smoke";
    private static final HexFormat HEX = HexFormat.of();

    private final TenantFixture tenantA = new TenantFixture(
            "A", UUID.fromString("12d7dca8-380c-5a6c-84ae-953502959486"),
            UUID.fromString("a5100000-0000-4000-8000-000000000001"),
            UUID.fromString("a5100000-0000-4000-8000-000000000002"),
            UUID.fromString("a5100000-0000-4000-8000-000000000003"),
            UUID.fromString("a5100000-0000-4000-8000-000000000004"));
    private final TenantFixture tenantB = new TenantFixture(
            "B", UUID.fromString("978d86fe-2cb8-5d4b-9968-e2f738a5c3e2"),
            UUID.fromString("b5100000-0000-4000-8000-000000000001"),
            UUID.fromString("b5100000-0000-4000-8000-000000000002"),
            UUID.fromString("b5100000-0000-4000-8000-000000000003"),
            UUID.fromString("b5100000-0000-4000-8000-000000000004"));
    private final List<TenantFixture> tenants = List.of(tenantA, tenantB);
    private final List<Map<String, Object>> evidence = new ArrayList<>();
    private final AcceptanceEligibility eligibility = new AcceptanceEligibility();
    private final ConcurrentHashMap<UUID, AtomicInteger> mainDeliveryCounts =
            new ConcurrentHashMap<>();

    private String runId;
    private String nameServer;
    private JdbcTemplate ticketAdmin;
    private JdbcTemplate ticketRuntime;
    private JdbcTemplate workflowAdmin;
    private JdbcTemplate workflowRuntime;
    private JdbcTransactionManager ticketTransactions;
    private JdbcTransactionManager workflowTransactions;
    private WorkflowStartDispatchService ticketRepository;
    private WorkflowStartConsumer workflowConsumer;
    private WorkflowStartDlqAcceptor dlqAcceptor;
    private WorkflowRecoveryMapper recoveryMapper;
    private WorkflowRecoveryAcceptanceService replayTransaction;
    private WorkflowRecoveryExecutionService replaySettlement;
    private WorkflowRecoveryReplayWorker replayWorker;
    private WorkflowStartRocketMqConsumers mqConsumers;
    private RocketMqTicketOutboxTransport ticketTransport;
    private DefaultMQProducer rawTicketProducer;
    private DefaultMQAdminExt mqAdmin;
    private ExecutorService replayExecutor;
    private long secondaryDlqInitialMessages;

    /**
     * 检查验收环境并启动隔离数据库及真实消息代理协作组件，准备完整故障场景。
     */
    @BeforeAll
    void startIsolatedAcceptance() throws Exception {
        requireExactEnvironment("FLOWDESK_M5_ACCEPTANCE_ISOLATED", "true");
        runId = requiredEnvironment("FLOWDESK_M5_ACCEPTANCE_RUN_ID");
        if (!runId.matches("[a-z0-9][a-z0-9-]{5,47}")) {
            throw new IllegalArgumentException(
                    "FLOWDESK_M5_ACCEPTANCE_RUN_ID must be a lowercase isolated-run identifier");
        }
        nameServer = requiredEnvironment("FLOWDESK_ROCKETMQ_NAME_SERVER");

        DriverManagerDataSource ticketAdminDataSource = dataSource(
                requiredEnvironment("FLOWDESK_M5_TICKET_ADMIN_DB_URL"), "root",
                requiredEnvironment("FLOWDESK_MYSQL_ROOT_PASSWORD"));
        DriverManagerDataSource workflowAdminDataSource = dataSource(
                requiredEnvironment("FLOWDESK_M5_WORKFLOW_ADMIN_DB_URL"), "root",
                requiredEnvironment("FLOWDESK_MYSQL_ROOT_PASSWORD"));
        DriverManagerDataSource ticketRuntimeDataSource = dataSource(
                requiredEnvironment("FLOWDESK_TICKET_DB_URL"), "fd_ticket_runtime",
                requiredEnvironment("FLOWDESK_TICKET_DB_PASSWORD"));
        DriverManagerDataSource workflowRuntimeDataSource = dataSource(
                requiredEnvironment("FLOWDESK_WORKFLOW_DB_URL"), "fd_workflow_runtime",
                requiredEnvironment("FLOWDESK_WORKFLOW_DB_PASSWORD"));

        ticketAdmin = new JdbcTemplate(ticketAdminDataSource);
        workflowAdmin = new JdbcTemplate(workflowAdminDataSource);
        ticketRuntime = new JdbcTemplate(ticketRuntimeDataSource);
        workflowRuntime = new JdbcTemplate(workflowRuntimeDataSource);
        ticketTransactions = new JdbcTransactionManager(ticketRuntimeDataSource);
        workflowTransactions = new JdbcTransactionManager(workflowRuntimeDataSource);
        ticketRepository = new WorkflowStartDispatchServiceImpl(com.flowdesk.workflow.support.WorkflowTestMappers.mapper(ticketRuntime.getDataSource(), com.flowdesk.ticket.outbox.TicketOutboxPersistenceMapper.class));

        assertFreshIsolatedDatabase();
        seedAcceptanceDefinitionsAndCatalogs();

        WorkflowStartEnvelopeGuard guard = new WorkflowStartEnvelopeGuard();
        WorkflowStartMapper startMapper = com.flowdesk.workflow.support.WorkflowTestMappers.mapper(workflowRuntime.getDataSource(), WorkflowStartMapper.class);
        WorkflowStartQueryService queries = new WorkflowStartQueryServiceImpl(startMapper);
        WorkflowStartCommandService startTransaction = new WorkflowStartCommandServiceImpl(
                startMapper, workflowTransactions);
        workflowConsumer = new WorkflowStartConsumer(
                guard, queries, startTransaction, eligibility);
        dlqAcceptor = new WorkflowStartDlqAcceptor(
                guard, new WorkflowStartDlqServiceImpl(startMapper, workflowTransactions));
        recoveryMapper = com.flowdesk.workflow.support.WorkflowTestMappers.mapper(workflowRuntime.getDataSource(), WorkflowRecoveryMapper.class);
        replayTransaction = new WorkflowRecoveryAcceptanceServiceImpl(
                recoveryMapper, workflowTransactions);
        replaySettlement = new WorkflowRecoveryExecutionServiceImpl(
                recoveryMapper, workflowTransactions);
        replayExecutor = Executors.newSingleThreadExecutor();
        TaskExecutor taskExecutor = task -> replayExecutor.execute(task);
        replayWorker = new WorkflowRecoveryReplayWorker(
                taskExecutor, workflowConsumer, replaySettlement);

        WorkflowStartRocketMqProperties consumerProperties = new WorkflowStartRocketMqProperties();
        consumerProperties.setEnabled(true);
        consumerProperties.setNameServer(nameServer);
        consumerProperties.setAccessKey(requiredEnvironment(
                "FLOWDESK_WORKFLOW_ROCKETMQ_ACCESS_KEY"));
        consumerProperties.setSecretKey(requiredEnvironment(
                "FLOWDESK_WORKFLOW_ROCKETMQ_SECRET_KEY"));
        mqConsumers = new WorkflowStartRocketMqConsumers(
                consumerProperties, this::observeMainDelivery, dlqAcceptor::accept);

        TicketOutboxPublisherProperties producerProperties = producerProperties(nameServer);
        ticketTransport = RocketMqTicketOutboxTransport.create(producerProperties);
        rawTicketProducer = rawProducer(
                TicketEventContract.WorkflowStartRequested.PRODUCER_GROUP,
                ticketHook(), nameServer, "acceptance-raw");
        mqAdmin = new DefaultMQAdminExt(adminHook(), 5_000L);
        mqAdmin.setAdminExtGroup("flowdesk_m5_acceptance_admin");
        mqAdmin.setNamesrvAddr(nameServer);
        mqAdmin.setInstanceName("flowdesk-m5-acceptance-admin-" + runId);

        mqAdmin.start();
        secondaryDlqInitialMessages = topicMessageCount(SECONDARY_DLQ);
        mqConsumers.start();
        rawTicketProducer.start();
        ticketTransport.start();
    }

    /**
     * 关闭验收组件并输出事实证据，即使单个清理失败仍继续释放剩余资源。
     */
    @AfterAll
    void stopAndWriteEvidence() throws Exception {
        try {
            unlockWorkflowRuntime();
        }
        finally {
            if (ticketTransport != null) {
                ticketTransport.close();
            }
            if (rawTicketProducer != null) {
                rawTicketProducer.shutdown();
            }
            if (mqConsumers != null) {
                mqConsumers.stop();
            }
            if (mqAdmin != null) {
                mqAdmin.shutdown();
            }
            if (replayExecutor != null) {
                replayExecutor.shutdownNow();
            }
        }

        assertThat(evidence).hasSize(2 + FROZEN_SCENARIOS.size() * tenants.size());
        for (String scenario : FROZEN_SCENARIOS) {
            assertThat(evidence.stream()
                    .filter(item -> scenario.equals(item.get("scenario")))
                    .map(item -> item.get("tenant")))
                    .containsExactlyInAnyOrder("A", "B");
        }
        writeEvidenceIfRequested();
    }

    /**
     * 验证两个租户的正常链路只提交冻结契约要求的业务副作用，记录真实基础设施中的消息身份和最终持久化事实。
     */
    @Test
    @Order(1)
    void m5HappyPathCommitsOnlyFrozenEffectsForBothTenants() {
        List<ScenarioFixture> fixtures = fixtures("M5-HAPPY");
        fixtures.forEach(fixture -> seedTicket(fixture, "PENDING", 0, null));

        TicketOutboxPublisher.PublishRunResult result = publisher(
                Clock.systemUTC(), ticketTransport).publishOnce();

        assertThat(result.published()).isEqualTo(2);
        for (ScenarioFixture fixture : fixtures) {
            awaitProcessed(fixture);
            assertHappyPath(fixture, 1);
            assertImmutableMessageFactsDeniedToRuntime(fixture);
            record("M5-HAPPY", fixture, Map.of("path", "TicketOutbox-RocketMQ-Workflow"));
        }
    }

    /**
     * 验证过期发送前租约可被接管且消息身份不变，记录真实基础设施中的消息身份和最终持久化事实。
     */
    @Test
    @Order(2)
    void m5F01ExpiredPreSendClaimsAreReclaimedWithoutChangingIdentity() {
        List<ScenarioFixture> fixtures = fixtures("M5-F01");
        Instant claimedAt = now();
        fixtures.forEach(fixture -> seedTicket(fixture, "PENDING", 0, null));
        Map<UUID, ClaimedOutbox> abandoned = new LinkedHashMap<>();
        for (ScenarioFixture fixture : fixtures) {
            abandoned.put(fixture.outboxId(), concurrentClaim(fixture, claimedAt));
        }

        MutableClock clock = new MutableClock(claimedAt.plusSeconds(31));
        TicketOutboxPublisher.PublishRunResult result = publisher(clock, ticketTransport).publishOnce();

        assertThat(result.published()).isEqualTo(2);
        for (ScenarioFixture fixture : fixtures) {
            awaitProcessed(fixture);
            assertHappyPath(fixture, 2);
            assertIdentityUnchanged(fixture, abandoned.get(fixture.outboxId()));
            record("M5-F01", fixture, Map.of(
                    "leaseSeconds", 30, "reclaimed", true, "attemptCount", 2));
        }
    }

    /**
     * 验证代理已接纳但本地未结算时重复发送最终收敛，记录真实基础设施中的消息身份和最终持久化事实。
     */
    @Test
    @Order(3)
    void m5F02BrokerAcceptedBeforeLocalCompletionConvergesAfterDuplicateSend() throws Exception {
        List<ScenarioFixture> fixtures = fixtures("M5-F02");
        Instant claimedAt = now();
        fixtures.forEach(fixture -> seedTicket(fixture, "PENDING", 0, null));
        Map<UUID, ClaimedOutbox> staleClaims = new LinkedHashMap<>();
        for (ScenarioFixture fixture : fixtures) {
            staleClaims.put(fixture.outboxId(), claim(fixture, claimedAt));
            assertThat(ticketTransport.send(wire(fixture).outboxMessage()).status())
                    .isEqualTo(TicketOutboxSendResult.Status.SEND_OK);
        }
        fixtures.forEach(this::awaitProcessed);

        MutableClock clock = new MutableClock(claimedAt.plusSeconds(31));
        TicketOutboxPublisher.PublishRunResult result = publisher(clock, ticketTransport).publishOnce();

        assertThat(result.published()).isEqualTo(2);
        for (ScenarioFixture fixture : fixtures) {
            assertHappyPath(fixture, 2);
            boolean staleCompletion = withTicketContext(fixture, () ->
                    new TransactionTemplate(ticketTransactions).execute(status ->
                            ticketRepository.markPublished(
                                    staleClaims.get(fixture.outboxId()), clock.instant(),
                                    clock.instant().minusSeconds(30))));
            assertThat(staleCompletion).isFalse();
            record("M5-F02", fixture, Map.of(
                    "duplicateSend", true, "staleCompletionRejected", true));
        }
    }

    /**
     * 验证发布器重启后已发布状态仍为终态，记录真实基础设施中的消息身份和最终持久化事实。
     */
    @Test
    @Order(4)
    void m5F03PublishedIsTerminalAcrossPublisherRestart() {
        List<ScenarioFixture> fixtures = fixtures("M5-F03");
        fixtures.forEach(fixture -> seedTicket(fixture, "PENDING", 0, null));
        assertThat(publisher(Clock.systemUTC(), ticketTransport).publishOnce().published())
                .isEqualTo(2);
        fixtures.forEach(this::awaitProcessed);

        TicketOutboxPublisher.PublishRunResult restarted = publisher(
                Clock.systemUTC(), ticketTransport).publishOnce();

        assertThat(restarted.scanned()).isZero();
        for (ScenarioFixture fixture : fixtures) {
            assertHappyPath(fixture, 1);
            record("M5-F03", fixture, Map.of(
                    "restartScanCount", 0, "terminalRegression", false));
        }
    }

    /**
     * 验证二至十次精确重复投递只产生一次业务副作用，记录真实基础设施中的消息身份和最终持久化事实。
     */
    @Test
    @Order(5)
    void m5F04TwoToTenExactDeliveriesHaveOneBusinessEffect() throws Exception {
        List<ScenarioFixture> fixtures = fixtures("M5-F04");
        fixtures.forEach(fixture -> seedTicket(fixture, "PENDING", 0, null));
        assertThat(publisher(Clock.systemUTC(), ticketTransport).publishOnce().published())
                .isEqualTo(2);
        fixtures.forEach(this::awaitProcessed);

        for (ScenarioFixture fixture : fixtures) {
            for (int duplicate = 0; duplicate < 9; duplicate++) {
                assertThat(ticketTransport.send(wire(fixture).outboxMessage()).status())
                        .isEqualTo(TicketOutboxSendResult.Status.SEND_OK);
            }
        }
        awaitMainGroupDrained();

        for (ScenarioFixture fixture : fixtures) {
            await(Duration.ofSeconds(30), () ->
                            mainDeliveryCount(fixture.messageId()) == 10
                                    && queryMessages(WorkflowStartContract.TOPIC,
                                    fixture.messageId()).size() == 10,
                    "ten stored and delivered duplicate messages");
            List<MessageExt> brokerMessages = queryMessages(
                    WorkflowStartContract.TOPIC, fixture.messageId());
            assertThat(brokerMessages).hasSize(10);
            brokerMessages.forEach(message ->
                    assertWireIdentity(message, wire(fixture), WorkflowStartContract.TOPIC));
            assertWorkflowEffectCounts(fixture, 1, 1, 1, 1);
            record("M5-F04", fixture, Map.of(
                    "brokerStoredMessages", brokerMessages.size(),
                    "mainDeliveries", mainDeliveryCount(fixture.messageId()),
                    "businessEffects", 1));
        }
    }

    /**
     * 验证消息与意图内容冲突始终不覆盖 Inbox，记录真实基础设施中的消息身份和最终持久化事实。
     */
    @Test
    @Order(6)
    void m5F05MessageAndIntentContentConflictsNeverOverwriteInbox() throws Exception {
        List<ScenarioFixture> fixtures = fixtures("M5-F05");
        fixtures.forEach(fixture -> seedTicket(fixture, "PENDING", 0, null));
        assertThat(publisher(Clock.systemUTC(), ticketTransport).publishOnce().published())
                .isEqualTo(2);
        fixtures.forEach(this::awaitProcessed);

        for (ScenarioFixture fixture : fixtures) {
            byte[] original = workflowAdmin.queryForObject("""
                    SELECT body_bytes FROM inbox_message
                    WHERE tenant_id = ? AND message_id = ?
                    """, byte[].class, uuid(fixture.tenant().tenantId()), uuid(fixture.messageId()));
            byte[] changedMessageBody = addOptional(fixture.body(), "messageConflict", "true");
            sendRaw(wire(fixture).withBody(changedMessageBody));
            byte[] changedIntentBody = addOptional(fixture.body(), "intentConflict", "true");
            sendRaw(wire(fixture).withMessageId(id("M5-F05-intent", fixture.tenant(), "message"))
                    .withBody(changedIntentBody));
            await(Duration.ofSeconds(15), () -> workflowAuditCount(
                    fixture.tenant().tenantId(), fixture.ticketId(), "WORKFLOW_START_CONFLICT") == 2,
                    "both conflict audits");
            assertThat(workflowAdmin.queryForObject("""
                    SELECT body_bytes FROM inbox_message
                    WHERE tenant_id = ? AND message_id = ?
                    """, byte[].class, uuid(fixture.tenant().tenantId()), uuid(fixture.messageId())))
                    .containsExactly(original);
            assertWorkflowEffectCounts(fixture, 1, 1, 1, 3);
            record("M5-F05", fixture, Map.of(
                    "messageConflict", "ACK", "intentConflict", "ACK",
                    "originalInboxOverwritten", false));
        }
        awaitMainGroupDrained();
    }

    /**
     * 验证迟到与相同意图投递不回退终态，记录真实基础设施中的消息身份和最终持久化事实。
     */
    @Test
    @Order(7)
    void m5F06LateAndSameIntentDeliveriesNeverRegressTerminalState() throws Exception {
        List<ScenarioFixture> fixtures = fixtures("M5-F06");
        fixtures.forEach(fixture -> seedTicket(fixture, "PENDING", 0, null));
        assertThat(publisher(Clock.systemUTC(), ticketTransport).publishOnce().published())
                .isEqualTo(2);
        fixtures.forEach(this::awaitProcessed);

        for (ScenarioFixture fixture : fixtures) {
            sendRaw(wire(fixture).withMessageId(id("M5-F06-late", fixture.tenant(), "message")));
            sendRaw(wire(fixture));
        }
        awaitMainGroupDrained();

        for (ScenarioFixture fixture : fixtures) {
            assertHappyPath(fixture, 1);
            record("M5-F06", fixture, Map.of(
                    "terminalState", "PROCESSED", "regressed", false));
        }
    }

    /**
     * 验证认证租户篡改及跨租户版本引用均失败关闭，记录真实基础设施中的消息身份和最终持久化事实。
     */
    @Test
    @Order(8)
    void m5F07TicketAuthenticatedTenantTamperingAndCrossTenantVersionsFailClosed()
            throws Exception {
        List<ScenarioFixture> fixtures = fixtures("M5-F07");
        fixtures.forEach(fixture -> seedTicket(fixture, null, 0, null));

        for (ScenarioFixture fixture : fixtures) {
            TenantFixture other = fixture.tenant().equals(tenantA) ? tenantB : tenantA;
            sendRaw(wire(fixture).withProperty("fd_tenant_id", other.tenantId().toString()));
            awaitMainGroupDrained();
            assertThat(workflowAdmin.queryForObject("""
                    SELECT COUNT(*) FROM inbox_message
                    WHERE message_id = ?
                    """, Integer.class, uuid(fixture.messageId()))).isZero();

            byte[] crossTenantBody = WorkflowStartV1PayloadCodec.encode(
                    fixture.ticketId(), other.definitionId(), other.versionId(), 1, REQUESTER);
            ScenarioFixture rejectedFixture = fixture.withBody(crossTenantBody);
            Wire crossTenant = wire(rejectedFixture);
            sendRaw(crossTenant);
            await(Duration.ofSeconds(15), () -> workflowInboxStatus(
                    fixture.tenant().tenantId(), fixture.messageId()).equals("REJECTED"),
                    "cross-tenant deterministic rejection");

            assertThat(workflowCount("workflow_instance", fixture)).isZero();
            assertThat(workflowCount("approval_task", fixture)).isZero();
            assertThat(workflowAdmin.queryForObject("""
                    SELECT COUNT(*) FROM inbox_message
                    WHERE tenant_id = ? AND message_id = ?
                    """, Integer.class, uuid(other.tenantId()), uuid(fixture.messageId())))
                    .isZero();
            assertThat(workflowAdmin.queryForObject("""
                    SELECT last_error_code FROM inbox_message
                    WHERE tenant_id = ? AND message_id = ?
                    """, String.class, uuid(fixture.tenant().tenantId()), uuid(fixture.messageId())))
                    .isEqualTo("WORKFLOW_VERSION_REFERENCE_INVALID");
            assertWorkflowEffectCounts(rejectedFixture, 1, 0, 0, 1);
            assertWorkflowReverseOutboxCount(rejectedFixture, 0);
            record("M5-F07", rejectedFixture, Map.of(
                    "tamperedTenantEffects", 0, "crossTenantAssociation", false));
        }
        awaitMainGroupDrained();
    }

    /**
     * 验证ACL 只允许冻结工单发布身份和工作流消费组，记录真实基础设施中的消息身份和最终持久化事实。
     */
    @Test
    @Order(9)
    void m5F08AclAllowsOnlyFrozenTicketPublishAndWorkflowGroups() throws Exception {
        List<ScenarioFixture> fixtures = fixtures("M5-F08");
        fixtures.forEach(fixture -> seedTicket(fixture, "PENDING", 0, null));
        assertThat(publisher(Clock.systemUTC(), ticketTransport).publishOnce().published())
                .isEqualTo(2);
        fixtures.forEach(this::awaitProcessed);

        SendResult positive = sendRaw(wire(fixtures.get(0)));
        MessageQueue positiveQueue = positive.getMessageQueue();
        long positiveOffset = positive.getQueueOffset();
        expectDenied("uncredentialed publish", () -> sendOnce(
                null, "flowdesk_m5_uncredentialed_pg", wire(fixtures.get(0)).message(),
                "no-credentials"));
        expectPullDenied("Ticket Sub", ticketHook(), WorkflowStartContract.CONSUMER_GROUP,
                positiveQueue, positiveOffset, "ticket-sub");
        expectDenied("Workflow Pub", () -> sendOnce(
                workflowHook(), TicketEventContract.WorkflowStartRequested.PRODUCER_GROUP,
                wire(fixtures.get(0)).message(), "workflow-pub"));
        expectPullDenied("Workflow other Group", workflowHook(), "flowdesk_host_smoke_group",
                positiveQueue, positiveOffset, "workflow-other-group");
        expectTopicPullDenied("Workflow other Topic", workflowHook(),
                WorkflowStartContract.CONSUMER_GROUP, HOST_TOPIC, "workflow-other-topic");
        expectTopicPullDenied("Workflow secondary DLQ", workflowHook(),
                WorkflowStartContract.DLQ_ACCEPTOR_GROUP, SECONDARY_DLQ,
                "workflow-secondary-dlq");
        awaitMainGroupDrained();

        for (ScenarioFixture fixture : fixtures) {
            assertHappyPath(fixture, 1);
            record("M5-F08", fixture, Map.of(
                    "uncredentialed", "DENIED", "ticketSub", "DENIED",
                    "workflowPub", "DENIED", "workflowOtherGroup", "DENIED",
                    "workflowOtherTopic", "DENIED", "secondaryDlqSub", "DENIED"));
        }
    }

    /**
     * 验证不可用端点八次发送后原子进入工单恢复，记录真实基础设施中的消息身份和最终持久化事实。
     */
    @Test
    @Order(10)
    void m5F09EightUnavailableEndpointSendsAtomicallyEnterTicketRecovery() throws Exception {
        List<ScenarioFixture> fixtures = fixtures("M5-F09");
        fixtures.forEach(fixture -> seedTicket(fixture, "PENDING", 0, null));
        Instant base = now();
        MutableClock clock = new MutableClock(base);
        TicketOutboxPublisherProperties unavailableProperties = producerProperties(
                "127.0.0.1:1");
        try (RocketMqTicketOutboxTransport unavailable =
                     RocketMqTicketOutboxTransport.create(unavailableProperties)) {
            unavailable.start();
            TicketOutboxPublisher publisher = publisher(clock, unavailable);
            for (int attempt = 1; attempt <= 8; attempt++) {
                TicketOutboxPublisher.PublishRunResult result = publisher.publishOnce();
                if (attempt < 8) {
                    assertThat(result.rescheduled()).isEqualTo(2);
                    Duration expected = TicketEventContract.WorkflowStartRequested
                            .FAILURE_BACKOFFS.get(attempt - 1);
                    for (ScenarioFixture fixture : fixtures) {
                        assertNextAttempt(fixture, clock.instant().plus(expected));
                    }
                    clock.advance(expected);
                }
                else {
                    assertThat(result.recovered()).isEqualTo(2);
                }
            }
            assertThat(publisher.publishOnce().scanned()).isZero();
        }

        for (ScenarioFixture fixture : fixtures) {
            assertTicketRecovery(fixture, 8, false);
            assertWorkflowEffectCounts(fixture, 0, 0, 0, 0);
            assertWorkflowReverseOutboxCount(fixture, 0);
            record("M5-F09", fixture, Map.of(
                    "attemptCount", 8, "automaticNinthSend", false,
                    "backoffs", List.of("1s", "5s", "30s", "2m", "10m", "30m", "2h")));
        }
    }

    /**
     * 验证数据库故障经主队列死信后原子保存 Inbox 恢复，记录真实基础设施中的消息身份和最终持久化事实。
     */
    @Test
    @Order(11)
    void m5F10WorkflowDatabaseOutageReachesMainDlqThenAtomicInboxRecovery() {
        List<ScenarioFixture> fixtures = fixtures("M5-F10");
        fixtures.forEach(fixture -> seedTicket(fixture, "PENDING", 0, null));
        lockWorkflowRuntime();
        try {
            assertThatThrownBy(() -> workflowRuntime.queryForObject("SELECT 1", Integer.class))
                    .isInstanceOf(CannotGetJdbcConnectionException.class);
            assertThat(publisher(Clock.systemUTC(), ticketTransport).publishOnce().published())
                    .isEqualTo(2);
            for (ScenarioFixture fixture : fixtures) {
                MessageExt dlq = awaitMessage(MAIN_DLQ, fixture.messageId(), Duration.ofMinutes(8));
                assertWireIdentity(dlq, wire(fixture), MAIN_DLQ);
                assertThat(mainDeliveryCount(fixture.messageId()))
                        .isEqualTo(WorkflowStartContract.MAIN_DELIVERY_ATTEMPTS);
            }
        }
        finally {
            unlockWorkflowRuntime();
        }

        for (ScenarioFixture fixture : fixtures) {
            await(Duration.ofSeconds(60), () -> "RECOVERY_REQUIRED".equals(
                    workflowInboxStatus(fixture.tenant().tenantId(), fixture.messageId())),
                    "DLQ acceptor persistence");
            assertWorkflowRecovery(fixture, 6);
            assertTicketPublished(fixture, 1);
            assertWorkflowEffectCounts(fixture, 1, 0, 0, 1);
            assertWorkflowReverseOutboxCount(fixture, 0);
            assertThat(queryMessages(SECONDARY_DLQ, fixture.messageId())).isEmpty();
            record("M5-F10", fixture, Map.of(
                    "mainDeliveries", mainDeliveryCount(fixture.messageId()),
                    "mainDlqIdentityPreserved", true,
                    "uniqueInboxRecovery", true, "secondaryIsolationMessages", 0));
        }
    }

    /**
     * 验证未知发送结果重投同一身份并只收敛一次，记录真实基础设施中的消息身份和最终持久化事实。
     */
    @Test
    @Order(12)
    void m5F11UnknownResultResendsSameIdentityAndConvergesOnce() {
        List<ScenarioFixture> fixtures = fixtures("M5-F11");
        fixtures.forEach(fixture -> seedTicket(fixture, "PENDING", 0, null));
        Set<String> firstSend = ConcurrentHashMap.newKeySet();
        TicketOutboxTransport unknownOnce = message -> {
            TicketOutboxSendResult result = ticketTransport.send(message);
            if (firstSend.add(message.keys())) {
                throw new SocketTimeoutException("acceptance simulates a lost SEND_OK response");
            }
            return result;
        };
        MutableClock clock = new MutableClock(now());
        TicketOutboxPublisher publisher = publisher(clock, unknownOnce);
        assertThat(publisher.publishOnce().rescheduled()).isEqualTo(2);
        clock.advance(Duration.ofSeconds(1));
        assertThat(publisher.publishOnce().published()).isEqualTo(2);
        awaitMainGroupDrained();

        for (ScenarioFixture fixture : fixtures) {
            awaitProcessed(fixture);
            assertHappyPath(fixture, 2);
            record("M5-F11", fixture, Map.of(
                    "firstResult", "UNKNOWN", "sameIdentityResent", true,
                    "businessEffects", 1));
        }
    }

    /**
     * 验证人工重放与迟到成功竞争只有一个胜者且无状态回退，记录真实基础设施中的消息身份和最终持久化事实。
     */
    @Test
    @Order(13)
    void m5F12ManualReplayAndLateBrokerSuccessHaveOneWinnerAndNoRegression()
            throws Exception {
        List<ScenarioFixture> fixtures = fixtures("M5-F12");
        fixtures.forEach(fixture -> seedTicket(fixture, "PUBLISHED", 1, now()));

        for (ScenarioFixture fixture : fixtures) {
            WorkflowStartConsumeResult accepted = dlqAcceptor.accept(
                    wire(fixture).dlqTransport());
            assertThat(accepted.code()).isEqualTo("WORKFLOW_START_RECOVERY_REQUIRED");
            UUID recoveryId = binaryUuid(workflowAdmin.queryForObject("""
                    SELECT recovery_id FROM message_recovery
                    WHERE tenant_id = ? AND direction = 'INBOX'
                      AND source_record_id = (
                        SELECT inbox_id FROM inbox_message
                        WHERE tenant_id = ? AND message_id = ?)
                    """, byte[].class, uuid(fixture.tenant().tenantId()),
                    uuid(fixture.tenant().tenantId()), uuid(fixture.messageId())));
            byte[] originalBody = workflowAdmin.queryForObject("""
                    SELECT body_bytes FROM inbox_message WHERE tenant_id = ? AND message_id = ?
                    """, byte[].class, uuid(fixture.tenant().tenantId()), uuid(fixture.messageId()));
            byte[] originalCorrelation = workflowAdmin.queryForObject("""
                    SELECT correlation_id FROM inbox_message WHERE tenant_id = ? AND message_id = ?
                    """, byte[].class, uuid(fixture.tenant().tenantId()), uuid(fixture.messageId()));
            Timestamp originalOccurredAt = workflowAdmin.queryForObject("""
                    SELECT occurred_at FROM inbox_message WHERE tenant_id = ? AND message_id = ?
                    """, Timestamp.class, uuid(fixture.tenant().tenantId()), uuid(fixture.messageId()));

            TenantContext human = TenantContext.ofHumanJwt(
                    fixture.tenant().tenantId(), ADMIN_USER, UUID.randomUUID());
            WorkflowRecoveryModels.Acceptance replay;
            try (TenantContextScope ignored = TenantContextScope.open(human)) {
                replay = replayTransaction.accept(human, recoveryId, ADMIN_USER,
                        "M5 F12 concurrent late-delivery acceptance", "m5-f12-" + fixture.tenant().label(), 0);
            }
            eligibility.installBarrier(fixture.ticketId(), 2);
            replayWorker.submit(replay.work());
            sendRaw(wire(fixture));

            await(Duration.ofSeconds(45), () -> "RESOLVED".equals(workflowAdmin.queryForObject("""
                    SELECT state FROM message_recovery WHERE tenant_id = ? AND recovery_id = ?
                    """, String.class, uuid(fixture.tenant().tenantId()), uuid(recoveryId))),
                    "concurrent replay resolution");
            awaitMainGroupDrained();
            replaySettlement.failed(replay.work(), "LATE_FAILURE_MUST_NOT_REGRESS");

            assertWorkflowEffectCounts(fixture, 1, 1, 1, 4);
            assertThat(ticketLinkStatus(fixture)).isEqualTo("REQUESTED");
            assertThat(workflowAdmin.queryForObject("""
                    SELECT body_bytes FROM inbox_message WHERE tenant_id = ? AND message_id = ?
                    """, byte[].class, uuid(fixture.tenant().tenantId()), uuid(fixture.messageId())))
                    .containsExactly(originalBody);
            assertThat(workflowAdmin.queryForObject("""
                    SELECT correlation_id FROM inbox_message WHERE tenant_id = ? AND message_id = ?
                    """, byte[].class, uuid(fixture.tenant().tenantId()), uuid(fixture.messageId())))
                    .containsExactly(originalCorrelation);
            assertThat(workflowAdmin.queryForObject("""
                    SELECT occurred_at FROM inbox_message WHERE tenant_id = ? AND message_id = ?
                    """, Timestamp.class, uuid(fixture.tenant().tenantId()), uuid(fixture.messageId())))
                    .isEqualTo(originalOccurredAt);
            assertThat(workflowAdmin.queryForObject("""
                    SELECT state FROM message_recovery WHERE tenant_id = ? AND recovery_id = ?
                    """, String.class, uuid(fixture.tenant().tenantId()), uuid(recoveryId)))
                    .isEqualTo("RESOLVED");
            record("M5-F12", fixture, Map.of(
                    "recoveryState", "RESOLVED", "singleWinner", true,
                    "lateFailureRegressed", false, "ticketLink", "REQUESTED"));
        }
    }

    /**
     * 验证第八次发布和第六次消费为准确终结边界，记录真实基础设施中的消息身份和最终持久化事实。
     */
    @Test
    @Order(14)
    void m5F13EighthPublishAndSixthConsumeAreExactTerminalBoundaries() {
        List<ScenarioFixture> publisherFixtures = fixtures("M5-F13-PUBLISHER");
        publisherFixtures.forEach(fixture -> seedTicket(fixture, "PENDING", 0, null));
        ConcurrentHashMap<UUID, AtomicInteger> publisherSendCounts = new ConcurrentHashMap<>();
        TicketOutboxTransport alwaysNotConfirmed = message -> {
            UUID messageId = UUID.fromString(message.keys());
            publisherSendCounts.computeIfAbsent(messageId, ignored -> new AtomicInteger())
                    .incrementAndGet();
            return TicketOutboxSendResult.notConfirmed(
                    "M5_BOUNDARY_NOT_CONFIRMED", "Acceptance boundary send was not confirmed.");
        };
        MutableClock clock = new MutableClock(now());
        TicketOutboxPublisher boundaryPublisher = publisher(clock, alwaysNotConfirmed);
        for (int attempt = 1; attempt <= 8; attempt++) {
            TicketOutboxPublisher.PublishRunResult result = boundaryPublisher.publishOnce();
            if (attempt < 8) {
                assertThat(result.rescheduled()).isEqualTo(2);
                clock.advance(TicketEventContract.WorkflowStartRequested
                        .FAILURE_BACKOFFS.get(attempt - 1));
            }
            else {
                assertThat(result.recovered()).isEqualTo(2);
            }
        }
        assertThat(boundaryPublisher.publishOnce().scanned()).isZero();
        for (ScenarioFixture fixture : publisherFixtures) {
            assertTicketRecovery(fixture, 8, false);
            assertWorkflowEffectCounts(fixture, 0, 0, 0, 0);
            assertWorkflowReverseOutboxCount(fixture, 0);
            assertThat(publisherSendCounts.get(fixture.messageId()).get()).isEqualTo(8);
        }

        List<ScenarioFixture> consumerFixtures = fixtures("M5-F13-CONSUMER");
        consumerFixtures.forEach(fixture -> seedTicket(fixture, "PENDING", 0, null));
        lockWorkflowRuntime();
        try {
            assertThat(publisher(Clock.systemUTC(), ticketTransport).publishOnce().published())
                    .isEqualTo(2);
            for (ScenarioFixture fixture : consumerFixtures) {
                MessageExt dlq = awaitMessage(
                        MAIN_DLQ, fixture.messageId(), Duration.ofMinutes(8));
                assertWireIdentity(dlq, wire(fixture), MAIN_DLQ);
                assertThat(mainDeliveryCount(fixture.messageId()))
                        .isEqualTo(WorkflowStartContract.MAIN_DELIVERY_ATTEMPTS);
            }
        }
        finally {
            unlockWorkflowRuntime();
        }

        for (int index = 0; index < tenants.size(); index++) {
            ScenarioFixture publish = publisherFixtures.get(index);
            ScenarioFixture consume = consumerFixtures.get(index);
            await(Duration.ofSeconds(60), () -> "RECOVERY_REQUIRED".equals(
                            workflowInboxStatus(consume.tenant().tenantId(), consume.messageId())),
                    "F13 DLQ acceptor persistence");
            assertTicketPublished(consume, 1);
            assertWorkflowRecovery(consume, 6);
            assertWorkflowEffectCounts(consume, 1, 0, 0, 1);
            assertWorkflowReverseOutboxCount(consume, 0);
            assertThat(queryMessages(SECONDARY_DLQ, consume.messageId())).isEmpty();
            record("M5-F13", consume, Map.of(
                    "publisherMessageId", publish.messageId().toString(),
                    "publisherBodySha256", HEX.formatHex(publish.bodyHash()),
                    "publisherTicket", ticketSnapshot(publish),
                    "publisherWorkflow", workflowSnapshot(publish),
                    "publisherAttempts", publisherSendCounts.get(publish.messageId()).get(),
                    "publisherNinthAttempt", false,
                    "consumerDeliveries", mainDeliveryCount(consume.messageId()),
                    "producerDeadLetteredAt", nullValue(),
                    "consumerDeadLetteredAt", "SET"));
        }
        assertThat(topicMessageCount(SECONDARY_DLQ)).isEqualTo(secondaryDlqInitialMessages);
    }

    /**
     * 验证非法 V1 为毒消息而未知可选字段保持兼容，记录真实基础设施中的消息身份和最终持久化事实。
     */
    @Test
    @Order(15)
    void m5F14MalformedV1IsPoisonWhileUnknownOptionalPayloadIsCompatible()
            throws Exception {
        List<ScenarioFixture> fixtures = fixtures("M5-F14").stream()
                .map(fixture -> fixture.withBody(addOptional(
                        fixture.body(), "consumerFirstOptionalHint", "accepted")))
                .toList();
        fixtures.forEach(fixture -> seedTicket(fixture, "PUBLISHED", 1, now()));

        for (ScenarioFixture fixture : fixtures) {
            String body = new String(fixture.body(), StandardCharsets.UTF_8);
            byte[] missing = body.replace(
                    "\"requesterUserId\":\"" + REQUESTER + "\",", "")
                    .getBytes(StandardCharsets.UTF_8);
            byte[] wrongType = body.replace("\"versionNo\":1", "\"versionNo\":\"1\"")
                    .getBytes(StandardCharsets.UTF_8);
            sendRaw(wire(fixture).withBody(missing));
            sendRaw(wire(fixture).withBody(wrongType));
            sendRaw(wire(fixture).withProperty("fd_unknown_reserved", "1"));
            awaitMainGroupDrained();
            assertThat(workflowAdmin.queryForObject("""
                    SELECT COUNT(*) FROM inbox_message WHERE tenant_id = ? AND message_id = ?
                    """, Integer.class, uuid(fixture.tenant().tenantId()), uuid(fixture.messageId())))
                    .isZero();

            sendRaw(wire(fixture));
            awaitProcessed(fixture);
            assertWorkflowEffectCounts(fixture, 1, 1, 1, 1);
            record("M5-F14", fixture, Map.of(
                    "missingFieldEffects", 0, "wrongTypeEffects", 0,
                    "unknownReservedEffects", 0, "optionalPayload", "PROCESSED"));
        }
        awaitMainGroupDrained();
    }

    /**
     * 断言当前场景的验收数据库为空且与生产环境隔离，失败时直接报告对应证据差异。
     */
    private void assertFreshIsolatedDatabase() {
        for (TenantFixture tenant : tenants) {
            assertThat(ticketAdmin.queryForObject("""
                    SELECT COUNT(*) FROM ticket WHERE tenant_id = ? AND ticket_no LIKE 'M5A-%'
                    """, Integer.class, uuid(tenant.tenantId()))).isZero();
            assertThat(workflowAdmin.queryForObject("""
                    SELECT COUNT(*) FROM workflow_definition
                    WHERE tenant_id = ? AND definition_code LIKE 'M5_ACCEPTANCE_%'
                    """, Integer.class, uuid(tenant.tenantId()))).isZero();
            assertThat(ticketAdmin.queryForObject("""
                    SELECT COUNT(*) FROM sla_policy WHERE tenant_id = ? AND sla_policy_id = ?
                    """, Integer.class, uuid(tenant.tenantId()), uuid(DEMO_SLA))).isEqualTo(1);
        }
    }

    /**
     * 建立当前测试场景的持久化前置事实，保留指定租户、身份和状态供后续业务路径验证。
     */
    private void seedAcceptanceDefinitionsAndCatalogs() {
        for (TenantFixture tenant : tenants) {
            workflowAdmin.update("""
                    INSERT INTO workflow_definition
                      (tenant_id, definition_id, definition_code, definition_name, status,
                       lock_version, created_by_type, created_by_id, updated_by_type, updated_by_id)
                    VALUES (?, ?, ?, ?, 'ACTIVE', 0, 'SERVICE', NULL, 'SERVICE', NULL)
                    """, uuid(tenant.tenantId()), uuid(tenant.definitionId()),
                    "M5_ACCEPTANCE_" + tenant.label(),
                    "M5 acceptance workflow " + tenant.label());
            workflowAdmin.update("""
                    INSERT INTO workflow_draft
                      (tenant_id, draft_id, definition_id, status, condition_field_code,
                       condition_operator, condition_value_type, condition_value_text,
                       approval_level_count, level1_approver_user_id, level2_approver_user_id,
                       validation_state, validation_digest, validated_at, consumed_at,
                       lock_version, created_at, created_by_type, created_by_id,
                       updated_at, updated_by_type, updated_by_id)
                    VALUES (?, ?, ?, 'CONSUMED', 'SERVICE_RISK_LEVEL', 'EQUALS', 'STRING',
                            'HIGH', 2, ?, ?, 'VALID', UNHEX(SHA2(?, 256)),
                            UTC_TIMESTAMP(6) - INTERVAL 2 SECOND,
                            UTC_TIMESTAMP(6) - INTERVAL 1 SECOND, 0,
                            UTC_TIMESTAMP(6) - INTERVAL 3 SECOND, 'SERVICE', NULL,
                            UTC_TIMESTAMP(6), 'SERVICE', NULL)
                    """, uuid(tenant.tenantId()), uuid(tenant.draftId()),
                    uuid(tenant.definitionId()), uuid(APPROVER_1), uuid(APPROVER_2),
                    "m5-acceptance-draft-" + tenant.label());
            workflowAdmin.update("""
                    INSERT INTO workflow_version
                      (tenant_id, workflow_version_id, definition_id, version_no,
                       source_draft_id, status, condition_field_code, condition_operator,
                       condition_value_type, condition_value_text, approval_level_count,
                       level1_approver_user_id, level2_approver_user_id, snapshot_sha256,
                       published_at, published_by_type, published_by_id)
                    VALUES (?, ?, ?, 1, ?, 'PUBLISHED', 'SERVICE_RISK_LEVEL', 'EQUALS',
                            'STRING', 'HIGH', 2, ?, ?, UNHEX(SHA2(?, 256)), UTC_TIMESTAMP(6),
                            'SERVICE', NULL)
                    """, uuid(tenant.tenantId()), uuid(tenant.versionId()),
                    uuid(tenant.definitionId()), uuid(tenant.draftId()), uuid(APPROVER_1),
                    uuid(APPROVER_2), "m5-acceptance-version-" + tenant.label());
            ticketAdmin.update("""
                    INSERT INTO service_catalog_item
                      (tenant_id, catalog_item_id, item_code, item_name, description,
                       risk_level, requires_approval, workflow_definition_id,
                       workflow_version_id, workflow_version_no, sla_policy_id, status,
                       lock_version, created_by_type, created_by_id,
                       updated_by_type, updated_by_id)
                    VALUES (?, ?, ?, ?, 'M5 isolated acceptance fixture', 'HIGH', 1,
                            ?, ?, 1, ?, 'ACTIVE', 0, 'SERVICE', NULL, 'SERVICE', NULL)
                    """, uuid(tenant.tenantId()), uuid(tenant.catalogId()),
                    "M5_ACCEPT_" + tenant.label(), "M5 acceptance catalog " + tenant.label(),
                    uuid(tenant.definitionId()), uuid(tenant.versionId()), uuid(DEMO_SLA));
        }
    }

    /**
     * 列出当前契约测试使用的冻结样例，确保两种通知来源均被覆盖。
     */
    private List<ScenarioFixture> fixtures(String scenario) {
        return tenants.stream().map(tenant -> fixture(scenario, tenant)).toList();
    }

    /**
     * 加载指定冻结属性样例，供协议一致性及错误变体测试复用。
     */
    private ScenarioFixture fixture(String scenario, TenantFixture tenant) {
        UUID ticketId = id(scenario, tenant, "ticket");
        UUID outboxId = id(scenario, tenant, "outbox");
        UUID messageId = id(scenario, tenant, "message");
        UUID correlationId = id(scenario, tenant, "correlation");
        byte[] body = WorkflowStartV1PayloadCodec.encode(
                ticketId, tenant.definitionId(), tenant.versionId(), 1, REQUESTER);
        byte[] bodyHash = sha256(body);
        byte[] intent = IdempotencyCodec.derivedKey(
                TicketEventContract.WorkflowStartRequested.BUSINESS_INTENT_KEY_NAMESPACE,
                tenant.tenantId(), ticketId);
        byte[] publication = IdempotencyCodec.publicationKey(
                TicketEventContract.WorkflowStartRequested.PUBLICATION_KEY_NAMESPACE,
                tenant.tenantId(), messageId);
        return new ScenarioFixture(scenario, tenant, ticketId, outboxId, messageId,
                correlationId, body, bodyHash, intent, publication);
    }

    /**
     * 建立当前测试场景的持久化前置事实，保留指定租户、身份和状态供后续业务路径验证。
     */
    private void seedTicket(ScenarioFixture fixture,
                            String outboxStatus,
                            int attemptCount,
                            Instant brokerConfirmedAt) {
        Instant created = now().minusSeconds(1);
        ticketAdmin.update("""
                INSERT INTO ticket
                  (tenant_id, ticket_id, ticket_no, creation_intent_key, origin_type,
                   catalog_item_id, requester_user_id, previous_attempt_ticket_id,
                   subject, description, status, assignee_user_id,
                   workflow_definition_id, workflow_version_id, workflow_version_no,
                   first_processable_at, resolved_at, closed_at, last_reopened_at,
                   reopen_count, terminal_at, lock_version, created_at,
                   created_by_type, created_by_id, updated_at, updated_by_type, updated_by_id)
                VALUES (?, ?, ?, ?, 'MANUAL', ?, ?, NULL,
                        'M5 isolated acceptance', 'No production data.',
                        'AWAITING_APPROVAL', NULL, ?, ?, 1,
                        NULL, NULL, NULL, NULL, 0, NULL, 0, ?,
                        'USER', ?, ?, 'USER', ?)
                """, uuid(fixture.tenant().tenantId()), uuid(fixture.ticketId()),
                ticketNumber(fixture), sha256((runId + fixture.scenario()
                        + fixture.tenant().label()).getBytes(StandardCharsets.UTF_8)),
                uuid(fixture.tenant().catalogId()), uuid(REQUESTER),
                uuid(fixture.tenant().definitionId()), uuid(fixture.tenant().versionId()),
                Timestamp.from(created), uuid(REQUESTER), Timestamp.from(created), uuid(REQUESTER));
        ticketAdmin.update("""
                INSERT INTO workflow_start_link
                  (tenant_id, ticket_id, start_intent_key, status, workflow_instance_id,
                   requested_at, established_at, recovery_required_at, recovery_reason_code,
                   retry_count, last_attempt_at, lock_version, created_at,
                   created_by_type, created_by_id, updated_at, updated_by_type, updated_by_id)
                VALUES (?, ?, ?, 'REQUESTED', NULL, ?, NULL, NULL, NULL,
                        0, NULL, 0, ?, 'USER', ?, ?, 'USER', ?)
                """, uuid(fixture.tenant().tenantId()), uuid(fixture.ticketId()),
                fixture.businessIntentKey(), Timestamp.from(created), Timestamp.from(created),
                uuid(REQUESTER), Timestamp.from(created), uuid(REQUESTER));
        if (outboxStatus == null) {
            return;
        }
        Instant nextAttempt = created;
        ticketAdmin.update("""
                INSERT INTO outbox_event
                  (tenant_id, outbox_id, message_id, publication_key, business_intent_key,
                   aggregate_type, aggregate_id, contract_ref, contract_version,
                   body_bytes, body_sha256, correlation_id, causation_id, status,
                   attempt_count, next_attempt_at, locked_by, locked_at,
                   last_error_code, broker_confirmed_at, lock_version, created_at,
                   created_by_type, created_by_id, updated_at, updated_by_type, updated_by_id)
                VALUES (?, ?, ?, ?, ?, 'TICKET', ?, 'flowdesk.workflow.start.v1', 1,
                        ?, ?, ?, NULL, ?, ?, ?, NULL, NULL, NULL, ?, 0, ?,
                        'USER', ?, ?, 'SERVICE', NULL)
                """, uuid(fixture.tenant().tenantId()), uuid(fixture.outboxId()),
                uuid(fixture.messageId()), fixture.publicationKey(), fixture.businessIntentKey(),
                uuid(fixture.ticketId()), fixture.body(), fixture.bodyHash(),
                uuid(fixture.correlationId()), outboxStatus, attemptCount,
                Timestamp.from(nextAttempt), nullableTimestamp(brokerConfirmedAt),
                Timestamp.from(created), uuid(REQUESTER), Timestamp.from(created));
    }

    /**
     * 在指定租户的短事务中领取场景 Outbox，返回包含当前令牌和版本的发送快照。
     */
    private ClaimedOutbox claim(
            ScenarioFixture fixture, Instant claimedAt) {
        Candidate candidate = ticketRepository.findCandidates(
                        claimedAt, claimedAt.minusSeconds(30), 50).stream()
                .filter(row -> row.outboxId().equals(fixture.outboxId()))
                .findFirst().orElseThrow();
        String token = UUID.randomUUID().toString();
        return withTicketContext(fixture, () ->
                new TransactionTemplate(ticketTransactions).execute(status ->
                        ticketRepository.claimForSend(candidate, token, claimedAt,
                                        claimedAt.minusSeconds(30))
                                .orElseThrow()));
    }

    /**
     * 同时启动两个领取事务，验证同一候选只允许一个处理者取得有效发送租约。
     */
    private ClaimedOutbox concurrentClaim(
            ScenarioFixture fixture, Instant claimedAt) {
        Candidate candidate = ticketRepository.findCandidates(
                        claimedAt, claimedAt.minusSeconds(30), 50).stream()
                .filter(row -> row.outboxId().equals(fixture.outboxId()))
                .findFirst().orElseThrow();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<ClaimedOutbox>> attempts = new ArrayList<>();
            for (int worker = 0; worker < 2; worker++) {
                attempts.add(executor.submit(() -> {
                    start.await();
                    String token = UUID.randomUUID().toString();
                    return withTicketContext(fixture, () ->
                            new TransactionTemplate(ticketTransactions).execute(status ->
                                    ticketRepository.claimForSend(candidate, token, claimedAt,
                                                    claimedAt.minusSeconds(30))
                                            .orElse(null)));
                }));
            }
            start.countDown();
            List<ClaimedOutbox> winners = new ArrayList<>();
            for (Future<ClaimedOutbox> attempt : attempts) {
                ClaimedOutbox claimed = attempt.get(10, TimeUnit.SECONDS);
                if (claimed != null) {
                    winners.add(claimed);
                }
            }
            assertThat(winners).singleElement();
            return winners.get(0);
        }
        catch (Exception failure) {
            throw new IllegalStateException("Real MySQL concurrent claim failed", failure);
        }
        finally {
            executor.shutdownNow();
        }
    }

    /**
     * 在测试租户上下文中执行或构造当前消息变体，保留原始场景事实供后续比较。
     */
    private <T> T withTicketContext(ScenarioFixture fixture,
                                    java.util.concurrent.Callable<T> work) {
        TenantContext context = TenantContext.ofPersistedTask(
                fixture.tenant().tenantId(),
                ActorRef.service(TicketEventContract.WorkflowStartRequested.PRODUCER_SERVICE),
                fixture.correlationId());
        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            return work.call();
        }
        catch (RuntimeException runtime) {
            throw runtime;
        }
        catch (Exception checked) {
            throw new IllegalStateException(checked);
        }
    }

    /**
     * 使用场景时钟和传输适配器创建工单发布器，复用真实仓储及事务边界。
     */
    private TicketOutboxPublisher publisher(Clock clock, TicketOutboxTransport transport) {
        return new TicketOutboxPublisher(
                ticketRepository, transport, ticketTransactions, clock);
    }

    /**
     * 在超时前等待消息处理事实提交，超时以场景上下文使测试失败。
     */
    private void awaitProcessed(ScenarioFixture fixture) {
        await(Duration.ofSeconds(30), () -> "PROCESSED".equals(workflowInboxStatus(
                fixture.tenant().tenantId(), fixture.messageId())), "Workflow Inbox PROCESSED");
    }

    /**
     * 断言当前场景的正常链路的全部冻结业务事实，失败时直接报告对应证据差异。
     */
    private void assertHappyPath(ScenarioFixture fixture, int attempts) {
        assertTicketPublished(fixture, attempts);
        assertWorkflowEffectCounts(fixture, 1, 1, 1, 1);
        assertOutboxAndInboxIdentity(fixture);
        assertWorkflowReverseOutboxCount(fixture, 0);
        assertThat(workflowAdmin.queryForObject("""
                SELECT COUNT(*) FROM message_recovery r
                JOIN inbox_message i ON i.tenant_id = r.tenant_id
                  AND i.inbox_id = r.source_record_id
                WHERE r.tenant_id = ? AND r.direction = 'INBOX'
                  AND i.business_intent_key = ?
                """, Integer.class, uuid(fixture.tenant().tenantId()),
                fixture.businessIntentKey())).isZero();
    }

    /**
     * 断言当前场景的稳定身份、终态或业务副作用，失败时直接报告对应证据差异。
     */
    private void assertTicketPublished(ScenarioFixture fixture, int attempts) {
        assertThat(ticketAdmin.queryForObject("""
                SELECT status FROM outbox_event WHERE tenant_id = ? AND outbox_id = ?
                """, String.class, uuid(fixture.tenant().tenantId()), uuid(fixture.outboxId())))
                .isEqualTo("PUBLISHED");
        assertThat(ticketAdmin.queryForObject("""
                SELECT attempt_count FROM outbox_event WHERE tenant_id = ? AND outbox_id = ?
                """, Integer.class, uuid(fixture.tenant().tenantId()), uuid(fixture.outboxId())))
                .isEqualTo(attempts);
        assertThat(ticketLinkStatus(fixture)).isEqualTo("REQUESTED");
        assertThat(ticketAdmin.queryForObject("""
                SELECT COUNT(*) FROM message_recovery
                WHERE tenant_id = ? AND direction = 'OUTBOX' AND source_record_id = ?
                """, Integer.class, uuid(fixture.tenant().tenantId()), uuid(fixture.outboxId())))
                .isZero();
    }

    /**
     * 断言当前场景的稳定身份、终态或业务副作用，失败时直接报告对应证据差异。
     */
    private void assertWorkflowReverseOutboxCount(ScenarioFixture fixture, int expected) {
        assertThat(workflowAdmin.queryForObject("""
                SELECT COUNT(*) FROM outbox_event WHERE tenant_id = ?
                  AND aggregate_id = ?
                """, Integer.class, uuid(fixture.tenant().tenantId()), uuid(fixture.ticketId())))
                .isEqualTo(expected);
    }

    /**
     * 断言当前场景的稳定身份、终态或业务副作用，失败时直接报告对应证据差异。
     */
    private void assertWorkflowEffectCounts(ScenarioFixture fixture,
                                            int inbox,
                                            int instance,
                                            int task,
                                            int audit) {
        assertThat(workflowAdmin.queryForObject("""
                SELECT COUNT(*) FROM inbox_message
                WHERE tenant_id = ? AND business_intent_key = ?
                """, Integer.class, uuid(fixture.tenant().tenantId()),
                fixture.businessIntentKey())).isEqualTo(inbox);
        assertThat(workflowCount("workflow_instance", fixture)).isEqualTo(instance);
        assertThat(workflowCount("approval_task", fixture)).isEqualTo(task);
        assertThat(workflowAdmin.queryForObject("""
                SELECT COUNT(*) FROM audit_record
                WHERE tenant_id = ? AND (
                  correlation_id = ?
                  OR target_id IN (
                    SELECT workflow_instance_id FROM workflow_instance
                    WHERE tenant_id = ? AND ticket_id = ?)
                  OR target_id IN (
                    SELECT r.recovery_id FROM message_recovery r
                    JOIN inbox_message i ON i.tenant_id = r.tenant_id
                      AND i.inbox_id = r.source_record_id
                    WHERE r.tenant_id = ? AND r.direction = 'INBOX'
                      AND i.business_intent_key = ?))
                """, Integer.class, uuid(fixture.tenant().tenantId()),
                uuid(fixture.correlationId()), uuid(fixture.tenant().tenantId()),
                uuid(fixture.ticketId()), uuid(fixture.tenant().tenantId()),
                fixture.businessIntentKey()))
                .isEqualTo(audit);
    }

    /**
     * 断言当前场景的稳定身份、终态或业务副作用，失败时直接报告对应证据差异。
     */
    private void assertImmutableMessageFactsDeniedToRuntime(ScenarioFixture fixture) {
        assertThatThrownBy(() -> ticketRuntime.update("""
                UPDATE outbox_event SET message_id = message_id
                WHERE tenant_id = ? AND outbox_id = ?
                """, uuid(fixture.tenant().tenantId()), uuid(fixture.outboxId())))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> ticketRuntime.update("""
                UPDATE outbox_event SET body_bytes = body_bytes
                WHERE tenant_id = ? AND outbox_id = ?
                """, uuid(fixture.tenant().tenantId()), uuid(fixture.outboxId())))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> workflowRuntime.update("""
                UPDATE inbox_message SET correlation_id = correlation_id
                WHERE tenant_id = ? AND message_id = ?
                """, uuid(fixture.tenant().tenantId()), uuid(fixture.messageId())))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> workflowRuntime.update("""
                UPDATE inbox_message SET body_bytes = body_bytes
                WHERE tenant_id = ? AND message_id = ?
                """, uuid(fixture.tenant().tenantId()), uuid(fixture.messageId())))
                .isInstanceOf(DataAccessException.class);
    }

    /**
     * 读取或统计当前场景的持久化及代理事实，为身份、状态和唯一副作用断言提供依据。
     */
    private int workflowCount(String table, ScenarioFixture fixture) {
        if ("workflow_instance".equals(table)) {
            return workflowAdmin.queryForObject("""
                    SELECT COUNT(*) FROM workflow_instance
                    WHERE tenant_id = ? AND ticket_id = ?
                    """, Integer.class, uuid(fixture.tenant().tenantId()),
                    uuid(fixture.ticketId()));
        }
        if ("approval_task".equals(table)) {
            return workflowAdmin.queryForObject("""
                    SELECT COUNT(*) FROM approval_task
                    WHERE tenant_id = ? AND workflow_instance_id IN (
                      SELECT workflow_instance_id FROM workflow_instance
                      WHERE tenant_id = ? AND ticket_id = ?)
                    """, Integer.class, uuid(fixture.tenant().tenantId()),
                    uuid(fixture.tenant().tenantId()), uuid(fixture.ticketId()));
        }
        throw new IllegalArgumentException("Unsupported count table " + table);
    }

    /**
     * 读取或统计当前场景的持久化及代理事实，为身份、状态和唯一副作用断言提供依据。
     */
    private int workflowAuditCount(UUID tenantId, UUID ticketId, String action) {
        return workflowAdmin.queryForObject("""
                SELECT COUNT(*) FROM audit_record
                WHERE tenant_id = ? AND action_code = ? AND target_id IN (
                  SELECT inbox_id FROM inbox_message
                  WHERE tenant_id = ? AND business_intent_key = (
                    SELECT start_intent_key FROM flowdesk_ticket.workflow_start_link
                    WHERE tenant_id = ? AND ticket_id = ?))
                """, Integer.class, uuid(tenantId), action, uuid(tenantId),
                uuid(tenantId), uuid(ticketId));
    }

    /**
     * 断言当前场景的发送和接纳两侧身份一致性，失败时直接报告对应证据差异。
     */
    private void assertOutboxAndInboxIdentity(ScenarioFixture fixture) {
        Map<String, Object> outbox = ticketAdmin.queryForMap("""
                SELECT LOWER(HEX(message_id)) AS message_id,
                       LOWER(HEX(body_sha256)) AS stored_hash,
                       LOWER(SHA2(body_bytes, 256)) AS actual_hash
                FROM outbox_event WHERE tenant_id = ? AND outbox_id = ?
                """, uuid(fixture.tenant().tenantId()), uuid(fixture.outboxId()));
        Map<String, Object> inbox = workflowAdmin.queryForMap("""
                SELECT LOWER(HEX(message_id)) AS message_id,
                       LOWER(HEX(body_sha256)) AS stored_hash,
                       LOWER(SHA2(body_bytes, 256)) AS actual_hash
                FROM inbox_message WHERE tenant_id = ? AND business_intent_key = ?
                """, uuid(fixture.tenant().tenantId()), fixture.businessIntentKey());
        String expectedId = fixture.messageId().toString().replace("-", "");
        String expectedHash = HEX.formatHex(fixture.bodyHash());
        assertThat(outbox.get("message_id")).isEqualTo(expectedId);
        assertThat(inbox.get("message_id")).isEqualTo(expectedId);
        assertThat(outbox.get("stored_hash")).isEqualTo(expectedHash);
        assertThat(outbox.get("actual_hash")).isEqualTo(expectedHash);
        assertThat(inbox.get("stored_hash")).isEqualTo(expectedHash);
        assertThat(inbox.get("actual_hash")).isEqualTo(expectedHash);
    }

    /**
     * 断言当前场景的稳定身份、终态或业务副作用，失败时直接报告对应证据差异。
     */
    private void assertIdentityUnchanged(
            ScenarioFixture fixture, ClaimedOutbox claimed) {
        assertThat(claimed.messageId()).isEqualTo(fixture.messageId());
        assertThat(claimed.bodyBytes()).containsExactly(fixture.body());
        assertThat(claimed.bodySha256()).containsExactly(fixture.bodyHash());
        assertOutboxAndInboxIdentity(fixture);
    }

    /**
     * 断言当前场景的下次尝试次数与重试时间，失败时直接报告对应证据差异。
     */
    private void assertNextAttempt(ScenarioFixture fixture, Instant expected) {
        Timestamp actual = ticketAdmin.queryForObject("""
                SELECT next_attempt_at FROM outbox_event WHERE tenant_id = ? AND outbox_id = ?
                """, Timestamp.class, uuid(fixture.tenant().tenantId()), uuid(fixture.outboxId()));
        assertThat(actual.toInstant()).isEqualTo(expected);
    }

    /**
     * 断言当前场景的稳定身份、终态或业务副作用，失败时直接报告对应证据差异。
     */
    private void assertTicketRecovery(
            ScenarioFixture fixture, int attempts, boolean deadLettered) {
        Map<String, Object> row = ticketAdmin.queryForMap("""
                SELECT o.status, o.attempt_count, o.locked_by, o.locked_at,
                       l.status AS link_status, l.retry_count,
                       r.state AS recovery_state, r.dead_lettered_at
                FROM outbox_event o
                JOIN workflow_start_link l
                  ON l.tenant_id = o.tenant_id AND l.ticket_id = o.aggregate_id
                JOIN message_recovery r
                  ON r.tenant_id = o.tenant_id AND r.direction = 'OUTBOX'
                 AND r.source_record_id = o.outbox_id
                WHERE o.tenant_id = ? AND o.outbox_id = ?
                """, uuid(fixture.tenant().tenantId()), uuid(fixture.outboxId()));
        assertThat(row.get("status")).isEqualTo("RECOVERY_REQUIRED");
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(attempts);
        assertThat(row.get("locked_by")).isNull();
        assertThat(row.get("locked_at")).isNull();
        assertThat(row.get("link_status")).isEqualTo("RECOVERY_REQUIRED");
        assertThat(((Number) row.get("retry_count")).intValue()).isEqualTo(attempts);
        assertThat(row.get("recovery_state")).isEqualTo("OPEN");
        assertThat(row.get("dead_lettered_at") != null).isEqualTo(deadLettered);
        assertThat(ticketAdmin.queryForObject("""
                SELECT COUNT(*) FROM message_recovery
                WHERE tenant_id = ? AND direction = 'OUTBOX' AND source_record_id = ?
                """, Integer.class, uuid(fixture.tenant().tenantId()), uuid(fixture.outboxId())))
                .isEqualTo(1);
    }

    /**
     * 断言当前场景的稳定身份、终态或业务副作用，失败时直接报告对应证据差异。
     */
    private void assertWorkflowRecovery(ScenarioFixture fixture, int attempts) {
        Map<String, Object> row = workflowAdmin.queryForMap("""
                SELECT i.status, i.attempt_count, LOWER(HEX(i.message_id)) AS message_id,
                       LOWER(HEX(i.body_sha256)) AS stored_hash,
                       LOWER(SHA2(i.body_bytes, 256)) AS actual_hash,
                       r.state AS recovery_state, r.dead_lettered_at
                FROM inbox_message i
                JOIN message_recovery r
                  ON r.tenant_id = i.tenant_id AND r.direction = 'INBOX'
                 AND r.source_record_id = i.inbox_id
                WHERE i.tenant_id = ? AND i.message_id = ?
                """, uuid(fixture.tenant().tenantId()), uuid(fixture.messageId()));
        assertThat(row.get("status")).isEqualTo("RECOVERY_REQUIRED");
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(attempts);
        assertThat(row.get("message_id"))
                .isEqualTo(fixture.messageId().toString().replace("-", ""));
        assertThat(row.get("stored_hash")).isEqualTo(HEX.formatHex(fixture.bodyHash()));
        assertThat(row.get("actual_hash")).isEqualTo(HEX.formatHex(fixture.bodyHash()));
        assertThat(row.get("recovery_state")).isEqualTo("OPEN");
        assertThat(row.get("dead_lettered_at")).isNotNull();
        assertThat(workflowAdmin.queryForObject("""
                SELECT COUNT(*) FROM message_recovery r
                JOIN inbox_message i ON i.tenant_id = r.tenant_id
                  AND i.inbox_id = r.source_record_id
                WHERE r.tenant_id = ? AND r.direction = 'INBOX' AND i.message_id = ?
                """, Integer.class, uuid(fixture.tenant().tenantId()), uuid(fixture.messageId())))
                .isEqualTo(1);
    }

    /**
     * 读取当前验收场景的业务快照或状态，供消息处理前后结果比较。
     */
    private String workflowInboxStatus(UUID tenantId, UUID messageId) {
        List<String> rows = workflowAdmin.query("""
                SELECT status FROM inbox_message WHERE tenant_id = ? AND message_id = ?
                """, (rs, row) -> rs.getString(1), uuid(tenantId), uuid(messageId));
        return rows.isEmpty() ? "MISSING" : rows.get(0);
    }

    /**
     * 读取当前验收场景的业务快照或状态，供消息处理前后结果比较。
     */
    private String ticketLinkStatus(ScenarioFixture fixture) {
        return ticketAdmin.queryForObject("""
                SELECT status FROM workflow_start_link WHERE tenant_id = ? AND ticket_id = ?
                """, String.class, uuid(fixture.tenant().tenantId()), uuid(fixture.ticketId()));
    }

    /**
     * 通过场景声明的真实或测试传输发送原消息，保留身份供重复投递与收敛结果核对。
     */
    private SendResult sendRaw(Wire wire) throws Exception {
        SendResult result = rawTicketProducer.send(wire.message(), 5000);
        assertThat(result.getSendStatus()).isEqualTo(SendStatus.SEND_OK);
        return result;
    }

    /**
     * 通过场景声明的真实或测试传输发送原消息，保留身份供重复投递与收敛结果核对。
     */
    private void sendOnce(RPCHook hook, String producerGroup, Message message, String suffix)
            throws Exception {
        DefaultMQProducer producer = rawProducer(producerGroup, hook, nameServer, suffix);
        try {
            producer.start();
            SendResult result = producer.send(message, 5000);
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("Broker did not return SEND_OK");
            }
        }
        finally {
            producer.shutdown();
        }
    }

    /**
     * 执行预期受限的代理操作并核验拒绝结果，证明错误身份或路由不能越过冻结边界。
     */
    private void expectPullDenied(String label,
                                  RPCHook hook,
                                  String group,
                                  MessageQueue queue,
                                  long offset,
                                  String suffix) throws Exception {
        DefaultMQPullConsumer consumer = pullConsumer(group, hook, suffix);
        try {
            expectDenied(label, () -> {
                consumer.start();
                consumer.pull(queue, "*", offset, 1, 5000);
            });
        }
        finally {
            consumer.shutdown();
        }
    }

    /**
     * 执行预期受限的代理操作并核验拒绝结果，证明错误身份或路由不能越过冻结边界。
     */
    private void expectTopicPullDenied(String label,
                                       RPCHook hook,
                                       String group,
                                       String topic,
                                       String suffix) throws Exception {
        DefaultMQPullConsumer consumer = pullConsumer(group, hook, suffix);
        try {
            expectDenied(label, () -> {
                consumer.start();
                Set<MessageQueue> queues = consumer.fetchSubscribeMessageQueues(topic);
                if (queues.isEmpty()) {
                    throw new IllegalStateException(label + " returned no queues; topology not proven");
                }
                consumer.pull(queues.iterator().next(), "*", 0, 1, 5000);
            });
        }
        finally {
            consumer.shutdown();
        }
    }

    /**
     * 执行预期受限的代理操作并核验拒绝结果，证明错误身份或路由不能越过冻结边界。
     */
    private void expectDenied(String label, CheckedOperation operation) throws Exception {
        try {
            operation.run();
        }
        catch (Throwable failure) {
            if (isAuthorizationFailure(failure)) {
                return;
            }
            throw new IllegalStateException(
                    label + " failed for a non-ACL reason", failure);
        }
        throw new IllegalStateException(label + " unexpectedly succeeded");
    }

    /**
     * 沿异常因果链检查代理无权限响应和认证错误，区分预期 ACL 拒绝与其他连接故障。
     */
    private static boolean isAuthorizationFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof MQBrokerException broker
                    && broker.getResponseCode() == ResponseCode.NO_PERMISSION) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("no permission")
                        || normalized.contains("not authorized")
                        || normalized.contains("authentication")
                        || normalized.contains("authorization")
                        || normalized.contains("signature")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 在超时前等待指定原身份消息到达代理，超时以场景上下文使测试失败。
     */
    private MessageExt awaitMessage(String topic, UUID messageId, Duration timeout) {
        final MessageExt[] found = new MessageExt[1];
        await(timeout, () -> {
            List<MessageExt> messages = queryMessages(topic, messageId);
            if (!messages.isEmpty()) {
                found[0] = messages.get(0);
                return true;
            }
            return false;
        }, "message " + messageId + " in " + topic);
        return found[0];
    }

    /**
     * 读取或统计当前场景的持久化及代理事实，为身份、状态和唯一副作用断言提供依据。
     */
    private List<MessageExt> queryMessages(String topic, UUID messageId) {
        try {
            QueryResult result = mqAdmin.queryMessage(
                    topic, messageId.toString(), 32,
                    System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1),
                    System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1));
            return result == null || result.getMessageList() == null
                    ? List.of()
                    : result.getMessageList().stream()
                    .filter(message -> messageId.toString().equals(message.getKeys()))
                    .toList();
        }
        catch (Exception notFoundYet) {
            String message = notFoundYet.getMessage();
            if (message != null && (message.toLowerCase(Locale.ROOT).contains("no message")
                    || message.toLowerCase(Locale.ROOT).contains("not found")
                    || message.toLowerCase(Locale.ROOT).contains("query message by key"))) {
                return List.of();
            }
            throw new IllegalStateException("Unable to query " + topic + " by message key", notFoundYet);
        }
    }

    /**
     * 读取或统计当前场景的持久化及代理事实，为身份、状态和唯一副作用断言提供依据。
     */
    private long topicMessageCount(String topic) {
        try {
            return mqAdmin.examineTopicStats(topic).getOffsetTable().values().stream()
                    .mapToLong(TopicOffset::getMaxOffset)
                    .sum();
        }
        catch (Exception failure) {
            throw new IllegalStateException("Unable to read topic offsets for " + topic, failure);
        }
    }

    /**
     * 记录当前验收步骤观察到的消息与持久化结果，供最终证据和次数边界断言。
     */
    private WorkflowStartConsumeResult observeMainDelivery(
            WorkflowStartTransportMessage delivery) {
        String messageId = delivery.userProperties().get("fd_message_id");
        if (messageId != null) {
            try {
                mainDeliveryCounts.computeIfAbsent(UUID.fromString(messageId), ignored ->
                        new AtomicInteger()).incrementAndGet();
            }
            catch (IllegalArgumentException ignored) {
                // The transport guard remains responsible for malformed identity handling.
            }
        }
        return workflowConsumer.consume(delivery);
    }

    /**
     * 读取或统计当前场景的持久化及代理事实，为身份、状态和唯一副作用断言提供依据。
     */
    private int mainDeliveryCount(UUID messageId) {
        AtomicInteger count = mainDeliveryCounts.get(messageId);
        return count == null ? 0 : count.get();
    }

    /**
     * 在超时前等待主消费组完成积压处理，超时以场景上下文使测试失败。
     */
    private void awaitMainGroupDrained() {
        await(Duration.ofSeconds(30), () -> {
            try {
                return mqAdmin.examineConsumeStats(
                        WorkflowStartContract.CONSUMER_GROUP).computeTotalDiff() == 0;
            }
            catch (Exception failure) {
                return false;
            }
        }, "main consumer Group lag to reach zero");
    }

    /**
     * 断言当前场景的稳定身份、终态或业务副作用，失败时直接报告对应证据差异。
     */
    private static void assertWireIdentity(MessageExt message, Wire expected, String topic) {
        assertThat(message.getTopic()).isEqualTo(topic);
        assertThat(message.getKeys()).isEqualTo(expected.keys());
        assertThat(message.getBody()).containsExactly(expected.body());
        for (Map.Entry<String, String> property : expected.properties().entrySet()) {
            assertThat(message.getUserProperty(property.getKey())).isEqualTo(property.getValue());
        }
        assertThat(sha256(message.getBody()))
                .containsExactly(HEX.parseHex(expected.properties().get("fd_body_sha256")));
    }

    /**
     * 锁定验收专用工作流运行时数据库账号，模拟消费处理中数据库不可用的故障。
     */
    private void lockWorkflowRuntime() {
        workflowAdmin.execute("ALTER USER 'fd_workflow_runtime'@'%' ACCOUNT LOCK");
    }

    /**
     * 解除验收数据库账号锁定，供故障场景结束后恢复消费及清理环境。
     */
    private void unlockWorkflowRuntime() {
        if (workflowAdmin != null) {
            workflowAdmin.execute("ALTER USER 'fd_workflow_runtime'@'%' ACCOUNT UNLOCK");
        }
    }

    /**
     * 记录当前验收步骤观察到的消息与持久化结果，供最终证据和次数边界断言。
     */
    private void record(String scenario,
                        ScenarioFixture fixture,
                        Map<String, Object> assertions) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("scenario", scenario);
        item.put("tenant", fixture.tenant().label());
        item.put("tenantId", fixture.tenant().tenantId().toString());
        item.put("ticketId", fixture.ticketId().toString());
        item.put("outboxId", fixture.outboxId().toString());
        item.put("messageId", fixture.messageId().toString());
        item.put("bodySha256", HEX.formatHex(fixture.bodyHash()));
        item.put("ticket", ticketSnapshot(fixture));
        item.put("workflow", workflowSnapshot(fixture));
        item.put("assertions", assertions);
        evidence.add(item);
    }

    /**
     * 读取当前验收场景的业务快照或状态，供消息处理前后结果比较。
     */
    private Map<String, Object> ticketSnapshot(ScenarioFixture fixture) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        List<Map<String, Object>> outbox = ticketAdmin.queryForList("""
                SELECT status, attempt_count, LOWER(HEX(message_id)) AS message_id,
                       LOWER(HEX(body_sha256)) AS body_sha256,
                       LOWER(SHA2(body_bytes, 256)) AS computed_sha256,
                       CASE WHEN broker_confirmed_at IS NULL THEN 0 ELSE 1 END
                         AS broker_confirmed
                FROM outbox_event WHERE tenant_id = ? AND outbox_id = ?
                """, uuid(fixture.tenant().tenantId()), uuid(fixture.outboxId()));
        snapshot.put("outbox", outbox.isEmpty() ? null : safeRow(outbox.get(0)));
        snapshot.put("linkStatus", ticketLinkStatus(fixture));
        List<Map<String, Object>> recovery = ticketAdmin.queryForList("""
                SELECT state, attempt_count,
                       CASE WHEN dead_lettered_at IS NULL THEN 0 ELSE 1 END AS dead_lettered
                FROM message_recovery
                WHERE tenant_id = ? AND direction = 'OUTBOX' AND source_record_id = ?
                """, uuid(fixture.tenant().tenantId()), uuid(fixture.outboxId()));
        snapshot.put("outboxRecovery", recovery.isEmpty() ? null : safeRow(recovery.get(0)));
        snapshot.put("outboxRecoveryCount", ticketAdmin.queryForObject("""
                SELECT COUNT(*) FROM message_recovery
                WHERE tenant_id = ? AND direction = 'OUTBOX' AND source_record_id = ?
                """, Integer.class, uuid(fixture.tenant().tenantId()), uuid(fixture.outboxId())));
        return snapshot;
    }

    /**
     * 读取当前验收场景的业务快照或状态，供消息处理前后结果比较。
     */
    private Map<String, Object> workflowSnapshot(ScenarioFixture fixture) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        List<Map<String, Object>> inbox = workflowAdmin.queryForList("""
                SELECT status, attempt_count, LOWER(HEX(message_id)) AS message_id,
                       LOWER(HEX(body_sha256)) AS body_sha256,
                       LOWER(SHA2(body_bytes, 256)) AS computed_sha256,
                       last_error_code
                FROM inbox_message WHERE tenant_id = ? AND business_intent_key = ?
                """, uuid(fixture.tenant().tenantId()), fixture.businessIntentKey());
        snapshot.put("inbox", inbox.isEmpty() ? null : safeRow(inbox.get(0)));
        snapshot.put("instanceCount", workflowCount("workflow_instance", fixture));
        snapshot.put("taskCount", workflowCount("approval_task", fixture));
        snapshot.put("reverseOutboxCount", workflowAdmin.queryForObject("""
                SELECT COUNT(*) FROM outbox_event
                WHERE tenant_id = ? AND aggregate_id = ?
                """, Integer.class, uuid(fixture.tenant().tenantId()),
                uuid(fixture.ticketId())));
        snapshot.put("auditCount", workflowAdmin.queryForObject("""
                SELECT COUNT(*) FROM audit_record
                WHERE tenant_id = ? AND (
                  correlation_id = ?
                  OR target_id IN (
                    SELECT workflow_instance_id FROM workflow_instance
                    WHERE tenant_id = ? AND ticket_id = ?)
                  OR target_id IN (
                    SELECT r.recovery_id FROM message_recovery r
                    JOIN inbox_message i ON i.tenant_id = r.tenant_id
                      AND i.inbox_id = r.source_record_id
                    WHERE r.tenant_id = ? AND r.direction = 'INBOX'
                      AND i.business_intent_key = ?))
                """, Integer.class, uuid(fixture.tenant().tenantId()),
                uuid(fixture.correlationId()), uuid(fixture.tenant().tenantId()),
                uuid(fixture.ticketId()), uuid(fixture.tenant().tenantId()),
                fixture.businessIntentKey()));
        snapshot.put("inboxRecoveryCount", workflowAdmin.queryForObject("""
                SELECT COUNT(*) FROM message_recovery r JOIN inbox_message i
                  ON i.tenant_id = r.tenant_id AND i.inbox_id = r.source_record_id
                WHERE r.tenant_id = ? AND r.direction = 'INBOX'
                  AND i.business_intent_key = ?
                """, Integer.class, uuid(fixture.tenant().tenantId()),
                fixture.businessIntentKey()));
        List<Map<String, Object>> recovery = workflowAdmin.queryForList("""
                SELECT r.state, r.attempt_count,
                       CASE WHEN r.dead_lettered_at IS NULL THEN 0 ELSE 1 END AS dead_lettered
                FROM message_recovery r JOIN inbox_message i
                  ON i.tenant_id = r.tenant_id AND i.inbox_id = r.source_record_id
                WHERE r.tenant_id = ? AND r.direction = 'INBOX'
                  AND i.business_intent_key = ?
                """, uuid(fixture.tenant().tenantId()), fixture.businessIntentKey());
        snapshot.put("inboxRecovery", recovery.isEmpty() ? null : safeRow(recovery.get(0)));
        return snapshot;
    }

    /**
     * 读取当前验收场景的业务快照或状态，供消息处理前后结果比较。
     */
    private static Map<String, Object> safeRow(Map<String, Object> row) {
        Map<String, Object> safe = new LinkedHashMap<>();
        row.forEach((key, value) -> safe.put(key, value instanceof Number
                ? ((Number) value).longValue() : value == null ? null : value.toString()));
        return safe;
    }

    /**
     * 将当前验收场景的原始观察和最终状态写入证据文件，便于复核故障处理结果。
     */
    private void writeEvidenceIfRequested() throws IOException {
        String target = System.getenv("FLOWDESK_M5_ACCEPTANCE_RESULT");
        if (target == null || target.isBlank()) {
            return;
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("marker", "FLOWDESK_MILESTONE_5_FAULT_MATRIX_OK");
        document.put("runId", runId);
        document.put("brokerVersion", "5.3.4");
        document.put("classicClientVersion", "5.3.1");
        document.put("tenantCount", tenants.size());
        document.put("frozenScenarioCount", FROZEN_SCENARIOS.size());
        document.put("results", evidence);
        Path path = Path.of(target).toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        Files.writeString(path,
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(document),
                StandardCharsets.UTF_8);
    }

    /**
     * 根据场景原持久化事实构造冻结启动信封，保持租户、业务键、时间和摘要的一致身份。
     */
    private Wire wire(ScenarioFixture fixture) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("fd_envelope_version", "1");
        properties.put("fd_event_type", "flowdesk.workflow.start");
        properties.put("fd_contract_ref", "flowdesk.workflow.start.v1");
        properties.put("fd_contract_version", "1");
        properties.put("fd_producer", "flowdesk-ticket-service");
        properties.put("fd_tenant_id", fixture.tenant().tenantId().toString());
        properties.put("fd_message_id", fixture.messageId().toString());
        properties.put("fd_business_intent_key", HEX.formatHex(fixture.businessIntentKey()));
        properties.put("fd_aggregate_type", "TICKET");
        properties.put("fd_aggregate_id", fixture.ticketId().toString());
        properties.put("fd_correlation_id", fixture.correlationId().toString());
        properties.put("fd_occurred_at", sixDigitInstant(
                ticketAdmin.queryForObject("""
                        SELECT created_at FROM ticket WHERE tenant_id = ? AND ticket_id = ?
                        """, Timestamp.class, uuid(fixture.tenant().tenantId()),
                        uuid(fixture.ticketId())).toInstant()));
        properties.put("fd_body_sha256", HEX.formatHex(fixture.bodyHash()));
        return new Wire(fixture.messageId().toString(), fixture.body(), properties);
    }

    /**
     * 向 JSON 顶层追加可选字段，构造协议向前兼容的载荷变体。
     */
    private static byte[] addOptional(byte[] body, String field, String value) {
        String json = new String(body, StandardCharsets.UTF_8);
        return (json.substring(0, json.length() - 1) + ",\"" + field + "\":\""
                + value + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 将时间截断为微秒并固定输出六位小数，匹配冻结消息契约的时间格式。
     */
    private static String sixDigitInstant(Instant instant) {
        Instant micros = instant.truncatedTo(ChronoUnit.MICROS);
        long seconds = micros.getEpochSecond();
        int micro = micros.getNano() / 1_000;
        return Instant.ofEpochSecond(seconds).toString().replace("Z", "")
                .replaceFirst("(?:\\.\\d+)?$", String.format(Locale.ROOT, ".%06d", micro)) + "Z";
    }

    /**
     * 构造指定代理端点的生产者配置，供正常和不可用端点故障场景复用。
     */
    private static TicketOutboxPublisherProperties producerProperties(String nameServer) {
        TicketOutboxPublisherProperties properties = new TicketOutboxPublisherProperties();
        properties.setEnabled(true);
        properties.setNameServer(nameServer);
        properties.setAccessKey(requiredEnvironment("FLOWDESK_TICKET_ROCKETMQ_ACCESS_KEY"));
        properties.setSecretKey(requiredEnvironment("FLOWDESK_TICKET_ROCKETMQ_SECRET_KEY"));
        return properties;
    }

    /**
     * 创建关闭 SDK 内部重试的验收生产者，明确指定访问身份、端点和独立实例名。
     */
    private DefaultMQProducer rawProducer(
            String group, RPCHook hook, String server, String suffix) {
        DefaultMQProducer producer = new DefaultMQProducer(group, hook);
        producer.setNamesrvAddr(server);
        producer.setInstanceName("flowdesk-m5-" + suffix + "-" + UUID.randomUUID());
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(5000);
        producer.setRetryTimesWhenSendFailed(0);
        producer.setRetryTimesWhenSendAsyncFailed(0);
        producer.setRetryAnotherBrokerWhenNotStoreOK(false);
        return producer;
    }

    /**
     * 构造消息拉取客户端，按指定测试身份观察队列与访问控制行为。
     */
    private DefaultMQPullConsumer pullConsumer(String group, RPCHook hook, String suffix) {
        DefaultMQPullConsumer consumer = new DefaultMQPullConsumer(group, hook);
        consumer.setNamesrvAddr(nameServer);
        consumer.setInstanceName("flowdesk-m5-" + suffix + "-" + UUID.randomUUID());
        consumer.setVipChannelEnabled(false);
        return consumer;
    }

    /**
     * 构造工单发布测试身份，供 ACL 正常及越权场景验证。
     */
    private RPCHook ticketHook() {
        return hook("FLOWDESK_TICKET_ROCKETMQ_ACCESS_KEY",
                "FLOWDESK_TICKET_ROCKETMQ_SECRET_KEY");
    }

    /**
     * 构造工作流消费测试身份，供真实代理队列与 ACL 断言。
     */
    private RPCHook workflowHook() {
        return hook("FLOWDESK_WORKFLOW_ROCKETMQ_ACCESS_KEY",
                "FLOWDESK_WORKFLOW_ROCKETMQ_SECRET_KEY");
    }

    /**
     * 构造消息代理管理员测试身份，供查询队列和验收证据读取。
     */
    private RPCHook adminHook() {
        return hook("FLOWDESK_ROCKETMQ_ADMIN_ACCESS_KEY",
                "FLOWDESK_ROCKETMQ_ADMIN_SECRET_KEY");
    }

    /**
     * 从指定环境变量构造代理 ACL 钩子，使用场景声明的精确身份。
     */
    private static RPCHook hook(String accessName, String secretName) {
        return new AclClientRPCHook(new SessionCredentials(
                requiredEnvironment(accessName), requiredEnvironment(secretName)));
    }

    /**
     * 根据验收连接信息构造测试数据源，保持每个权限账号的连接独立。
     */
    private static DriverManagerDataSource dataSource(
            String url, String username, String password) {
        if (!url.startsWith("jdbc:mysql://127.0.0.1:")
                && !url.startsWith("jdbc:mysql://localhost:")) {
            throw new IllegalArgumentException(
                    "M5 acceptance database URLs must target a loopback-isolated MySQL");
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    /**
     * 按场景、租户标签及记录种类派生稳定 UUID，便于将验收证据关联到同一业务事实。
     */
    private static UUID id(String scenario, TenantFixture tenant, String kind) {
        return UUID.nameUUIDFromBytes(("flowdesk:" + scenario + ":" + tenant.label()
                + ":" + kind).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 读取当前验收场景的业务快照或状态，供消息处理前后结果比较。
     */
    private static String ticketNumber(ScenarioFixture fixture) {
        return "M5A-" + fixture.scenario().replace("M5-", "") + "-"
                + fixture.tenant().label() + "-"
                + fixture.ticketId().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }

    /**
     * 在给定时间内轮询条件，超时抛出带场景信息的断言错误。
     */
    private static void await(Duration timeout, BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Throwable last = null;
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            }
            catch (RuntimeException transientFailure) {
                last = transientFailure;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(250);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + description,
                        interrupted);
            }
        }
        throw new IllegalStateException("Timed out waiting for " + description, last);
    }

    /**
     * 读取必填验收环境变量，缺失时立即失败以避免误用默认外部资源。
     */
    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required acceptance environment " + name);
        }
        return value.trim();
    }

    /**
     * 断言验收环境变量与冻结值一致，防止在错误基础设施上执行场景。
     */
    private static void requireExactEnvironment(String name, String expected) {
        if (!expected.equals(requiredEnvironment(name))) {
            throw new IllegalArgumentException(name + " must be exactly " + expected);
        }
    }

    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    /**
     * 按数据库二进制编码还原 UUID，供读取后的稳定身份比较。
     */
    private static UUID binaryUuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /**
     * 计算输入字节的 SHA-256 摘要，用于内容一致性核对及稳定业务键派生。
     */
    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 将可空时间转换为 JDBC 时间戳，保留数据库可空字段的语义。
     */
    private static Timestamp nullableTimestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    /**
     * 返回断言工具使用的 NULL 展示标记，便于统一比较数据库中缺失的字段值。
     */
    private static Object nullValue() {
        return "NULL";
    }

    /**
     * 冻结测试样例或传输快照，保留原始身份、字节和摘要供正常及错误变体场景复用。
     */
    private record TenantFixture(
            String label,
            UUID tenantId,
            UUID definitionId,
            UUID draftId,
            UUID versionId,
            UUID catalogId) {
    }

    /**
     * 冻结测试样例或传输快照，保留原始身份、字节和摘要供正常及错误变体场景复用。
     */
    private record ScenarioFixture(
            String scenario,
            TenantFixture tenant,
            UUID ticketId,
            UUID outboxId,
            UUID messageId,
            UUID correlationId,
            byte[] body,
            byte[] bodyHash,
            byte[] businessIntentKey,
            byte[] publicationKey) {

        /**
         * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
         */
        private ScenarioFixture {
            body = body.clone();
            bodyHash = bodyHash.clone();
            businessIntentKey = businessIntentKey.clone();
            publicationKey = publicationKey.clone();
        }

        /**
         * 返回消息体字节的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override
        public byte[] body() {
            return body.clone();
        }

        /**
         * 返回样例消息体摘要的副本，防止错误变体修改原始测试基线。
         */
        @Override
        public byte[] bodyHash() {
            return bodyHash.clone();
        }

        /**
         * 返回业务意图键的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override
        public byte[] businessIntentKey() {
            return businessIntentKey.clone();
        }

        /**
         * 返回消息发布键的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override
        public byte[] publicationKey() {
            return publicationKey.clone();
        }

        /**
         * 复制消息并替换消息体，供摘要一致或不一致的契约变体验证。
         */
        private ScenarioFixture withBody(byte[] replacement) {
            return new ScenarioFixture(scenario, tenant, ticketId, outboxId, messageId,
                    correlationId, replacement, sha256(replacement), businessIntentKey,
                    publicationKey);
        }
    }

    /**
     * 冻结测试样例或传输快照，保留原始身份、字节和摘要供正常及错误变体场景复用。
     */
    private record Wire(String keys, byte[] body, Map<String, String> properties) {

        /**
         * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
         */
        private Wire {
            body = body.clone();
            properties = Map.copyOf(properties);
        }

        /**
         * 复制消息并替换消息体，供摘要一致或不一致的契约变体验证。
         */
        private Wire withBody(byte[] replacement) {
            Map<String, String> changed = new LinkedHashMap<>(properties);
            changed.put("fd_body_sha256", HEX.formatHex(sha256(replacement)));
            return new Wire(keys, replacement, changed);
        }

        /**
         * 复制消息并替换身份，构造消息身份冲突或重复投递场景。
         */
        private Wire withMessageId(UUID replacement) {
            Map<String, String> changed = new LinkedHashMap<>(properties);
            changed.put("fd_message_id", replacement.toString());
            return new Wire(replacement.toString(), body, changed);
        }

        /**
         * 复制消息属性并替换单个字段，避免错误变体修改原始基线。
         */
        private Wire withProperty(String name, String value) {
            Map<String, String> changed = new LinkedHashMap<>(properties);
            changed.put(name, value);
            return new Wire(keys, body, changed);
        }

        /**
         * 将样例转换为真实代理消息，保留冻结属性与原始消息体。
         */
        private Message message() {
            Message message = new Message(WorkflowStartContract.TOPIC, body.clone());
            message.setKeys(keys);
            properties.forEach(message::putUserProperty);
            return message;
        }

        /**
         * 将样例转换为发送适配器输入，保持稳定身份与原始字节。
         */
        private TicketOutboxMessage outboxMessage() {
            return new TicketOutboxMessage(
                    WorkflowStartContract.TOPIC, keys, body, properties);
        }

        /**
         * 将样例转换为死信传输入口，沿用冻结信封及原始内容。
         */
        private WorkflowStartTransportMessage dlqTransport() {
            return new WorkflowStartTransportMessage(
                    WorkflowStartContract.DLQ_TOPIC,
                    WorkflowStartContract.DLQ_ACCEPTOR_GROUP,
                    keys, null, body, properties);
        }

        /**
         * 返回消息体字节的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    /**
     * 可由测试推进的时钟，供重试、租约过期和恢复续跑场景使用确定性时间。
     */
    private static final class MutableClock extends Clock {

        private Instant instant;

        /**
         * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
         */
        private MutableClock(Instant instant) {
            this.instant = instant.truncatedTo(ChronoUnit.MICROS);
        }

        /**
         * 推进测试时钟指定时长，触发到期重试或租约过期路径。
         */
        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        /**
         * 返回测试时钟的固定 UTC 时区，避免本地时区影响时间边界。
         */
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        /**
         * 按测试时钟支持的时区契约返回时钟，保持可控时间点不变。
         */
        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneId.of("UTC").equals(zone)) {
                throw new IllegalArgumentException("Acceptance clock is fixed to UTC");
            }
            return this;
        }

        /**
         * 返回测试时钟当前时间，供租约及重试场景确定性推进。
         */
        @Override
        public Instant instant() {
            return instant;
        }
    }

    /**
     * 可控制资格结果及依赖故障的测试替身，记录调用用于验证事务外预检和竞争处理。
     */
    private static final class AcceptanceEligibility
            implements WorkflowApproverEligibilityChecker {

        private final Map<UUID, CyclicBarrier> barriers = new ConcurrentHashMap<>();

        /**
         * 为指定场景安装并发屏障，确保多个请求在被测竞争点同步相遇。
         */
        private void installBarrier(UUID ticketId, int parties) {
            barriers.put(ticketId, new CyclicBarrier(parties,
                    () -> barriers.remove(ticketId)));
        }

        /**
         * 返回可配置的测试审批资格结果并记录调用次数，供预检、依赖失败及并发路径断言。
         */
        @Override
        public boolean isEligibleApprover(UUID tenantId, UUID approverUserId) {
            assertThat(approverUserId).isEqualTo(APPROVER_1);
            CyclicBarrier barrier = barriers.values().stream().findFirst().orElse(null);
            if (barrier != null) {
                try {
                    barrier.await(30, TimeUnit.SECONDS);
                }
                catch (Exception failure) {
                    throw new IllegalStateException("Acceptance replay barrier failed", failure);
                }
            }
            return true;
        }
    }

    /**
     * 工作流内部协作类型，组合当前处理所需的结构与行为，供所在用例的依赖链调用。
     */
    @FunctionalInterface
    private interface CheckedOperation {
        /**
         * 执行可抛受检异常的验收步骤，使故障场景能通过统一调用包装运行。
         */
        void run() throws Exception;
    }

    /**
     * 读取注入时钟并截断至数据库可保存的精度，便于持久化比较及测试。
     */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
