package com.flowdesk.workflow.start;

import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.ticket.event.TicketEventContract;
import com.flowdesk.ticket.event.WorkflowStartV1PayloadCodec;
import com.flowdesk.ticket.outbox.RocketMqTicketOutboxTransport;
import com.flowdesk.ticket.outbox.TicketOutboxMessage;
import com.flowdesk.ticket.outbox.TicketOutboxPublisher;
import com.flowdesk.ticket.outbox.TicketOutboxPublisherProperties;
import com.flowdesk.ticket.outbox.TicketOutboxRepository;
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
 * Destructive, opt-in M5 acceptance against an explicitly isolated MySQL 8.4 / RocketMQ 5.3.4
 * environment. It is intentionally disabled during ordinary Maven builds.
 *
 * <p>Every frozen F01..F14 scenario runs for both frozen demo tenants. Assertions read final rows
 * through the deployment/admin connection and record only identities, hashes, states and counts;
 * message bodies and credentials never enter the evidence file.</p>
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
    private TicketOutboxRepository ticketRepository;
    private WorkflowStartConsumer workflowConsumer;
    private WorkflowStartDlqAcceptor dlqAcceptor;
    private WorkflowRecoveryRepository recoveryRepository;
    private WorkflowRecoveryReplayTransaction replayTransaction;
    private WorkflowRecoveryReplaySettlement replaySettlement;
    private WorkflowRecoveryReplayWorker replayWorker;
    private WorkflowStartRocketMqConsumers mqConsumers;
    private RocketMqTicketOutboxTransport ticketTransport;
    private DefaultMQProducer rawTicketProducer;
    private DefaultMQAdminExt mqAdmin;
    private ExecutorService replayExecutor;
    private long secondaryDlqInitialMessages;

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
        ticketRepository = new TicketOutboxRepository(ticketRuntime);

        assertFreshIsolatedDatabase();
        seedAcceptanceDefinitionsAndCatalogs();

        WorkflowStartEnvelopeGuard guard = new WorkflowStartEnvelopeGuard();
        WorkflowStartRepository startRepository = new WorkflowStartRepository(workflowRuntime);
        WorkflowStartTransaction startTransaction = new WorkflowStartTransaction(
                startRepository, workflowTransactions);
        workflowConsumer = new WorkflowStartConsumer(
                guard, startRepository, startTransaction, eligibility);
        dlqAcceptor = new WorkflowStartDlqAcceptor(
                guard, new WorkflowStartDlqTransaction(startRepository, workflowTransactions));
        recoveryRepository = new WorkflowRecoveryRepository(workflowRuntime);
        replayTransaction = new WorkflowRecoveryReplayTransaction(
                recoveryRepository, workflowTransactions);
        replaySettlement = new WorkflowRecoveryReplaySettlement(
                recoveryRepository, workflowTransactions);
        replayExecutor = Executors.newSingleThreadExecutor();
        TaskExecutor taskExecutor = task -> replayExecutor.execute(task);
        replayWorker = new WorkflowRecoveryReplayWorker(
                taskExecutor, recoveryRepository, workflowConsumer, replaySettlement);

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

    @Test
    @Order(2)
    void m5F01ExpiredPreSendClaimsAreReclaimedWithoutChangingIdentity() {
        List<ScenarioFixture> fixtures = fixtures("M5-F01");
        Instant claimedAt = now();
        fixtures.forEach(fixture -> seedTicket(fixture, "PENDING", 0, null));
        Map<UUID, TicketOutboxRepository.ClaimedOutbox> abandoned = new LinkedHashMap<>();
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

    @Test
    @Order(3)
    void m5F02BrokerAcceptedBeforeLocalCompletionConvergesAfterDuplicateSend() throws Exception {
        List<ScenarioFixture> fixtures = fixtures("M5-F02");
        Instant claimedAt = now();
        fixtures.forEach(fixture -> seedTicket(fixture, "PENDING", 0, null));
        Map<UUID, TicketOutboxRepository.ClaimedOutbox> staleClaims = new LinkedHashMap<>();
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
            WorkflowRecoveryReplayTransaction.Acceptance replay;
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

    private List<ScenarioFixture> fixtures(String scenario) {
        return tenants.stream().map(tenant -> fixture(scenario, tenant)).toList();
    }

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

    private TicketOutboxRepository.ClaimedOutbox claim(
            ScenarioFixture fixture, Instant claimedAt) {
        TicketOutboxRepository.Candidate candidate = ticketRepository.findCandidates(
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

    private TicketOutboxRepository.ClaimedOutbox concurrentClaim(
            ScenarioFixture fixture, Instant claimedAt) {
        TicketOutboxRepository.Candidate candidate = ticketRepository.findCandidates(
                        claimedAt, claimedAt.minusSeconds(30), 50).stream()
                .filter(row -> row.outboxId().equals(fixture.outboxId()))
                .findFirst().orElseThrow();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<TicketOutboxRepository.ClaimedOutbox>> attempts = new ArrayList<>();
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
            List<TicketOutboxRepository.ClaimedOutbox> winners = new ArrayList<>();
            for (Future<TicketOutboxRepository.ClaimedOutbox> attempt : attempts) {
                TicketOutboxRepository.ClaimedOutbox claimed = attempt.get(10, TimeUnit.SECONDS);
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

    private TicketOutboxPublisher publisher(Clock clock, TicketOutboxTransport transport) {
        return new TicketOutboxPublisher(
                ticketRepository, transport, ticketTransactions, clock);
    }

    private void awaitProcessed(ScenarioFixture fixture) {
        await(Duration.ofSeconds(30), () -> "PROCESSED".equals(workflowInboxStatus(
                fixture.tenant().tenantId(), fixture.messageId())), "Workflow Inbox PROCESSED");
    }

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

    private void assertWorkflowReverseOutboxCount(ScenarioFixture fixture, int expected) {
        assertThat(workflowAdmin.queryForObject("""
                SELECT COUNT(*) FROM outbox_event WHERE tenant_id = ?
                  AND aggregate_id = ?
                """, Integer.class, uuid(fixture.tenant().tenantId()), uuid(fixture.ticketId())))
                .isEqualTo(expected);
    }

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

    private void assertIdentityUnchanged(
            ScenarioFixture fixture, TicketOutboxRepository.ClaimedOutbox claimed) {
        assertThat(claimed.messageId()).isEqualTo(fixture.messageId());
        assertThat(claimed.bodyBytes()).containsExactly(fixture.body());
        assertThat(claimed.bodySha256()).containsExactly(fixture.bodyHash());
        assertOutboxAndInboxIdentity(fixture);
    }

    private void assertNextAttempt(ScenarioFixture fixture, Instant expected) {
        Timestamp actual = ticketAdmin.queryForObject("""
                SELECT next_attempt_at FROM outbox_event WHERE tenant_id = ? AND outbox_id = ?
                """, Timestamp.class, uuid(fixture.tenant().tenantId()), uuid(fixture.outboxId()));
        assertThat(actual.toInstant()).isEqualTo(expected);
    }

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

    private String workflowInboxStatus(UUID tenantId, UUID messageId) {
        List<String> rows = workflowAdmin.query("""
                SELECT status FROM inbox_message WHERE tenant_id = ? AND message_id = ?
                """, (rs, row) -> rs.getString(1), uuid(tenantId), uuid(messageId));
        return rows.isEmpty() ? "MISSING" : rows.get(0);
    }

    private String ticketLinkStatus(ScenarioFixture fixture) {
        return ticketAdmin.queryForObject("""
                SELECT status FROM workflow_start_link WHERE tenant_id = ? AND ticket_id = ?
                """, String.class, uuid(fixture.tenant().tenantId()), uuid(fixture.ticketId()));
    }

    private SendResult sendRaw(Wire wire) throws Exception {
        SendResult result = rawTicketProducer.send(wire.message(), 5000);
        assertThat(result.getSendStatus()).isEqualTo(SendStatus.SEND_OK);
        return result;
    }

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

    private int mainDeliveryCount(UUID messageId) {
        AtomicInteger count = mainDeliveryCounts.get(messageId);
        return count == null ? 0 : count.get();
    }

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

    private void lockWorkflowRuntime() {
        workflowAdmin.execute("ALTER USER 'fd_workflow_runtime'@'%' ACCOUNT LOCK");
    }

    private void unlockWorkflowRuntime() {
        if (workflowAdmin != null) {
            workflowAdmin.execute("ALTER USER 'fd_workflow_runtime'@'%' ACCOUNT UNLOCK");
        }
    }

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

    private static Map<String, Object> safeRow(Map<String, Object> row) {
        Map<String, Object> safe = new LinkedHashMap<>();
        row.forEach((key, value) -> safe.put(key, value instanceof Number
                ? ((Number) value).longValue() : value == null ? null : value.toString()));
        return safe;
    }

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

    private static byte[] addOptional(byte[] body, String field, String value) {
        String json = new String(body, StandardCharsets.UTF_8);
        return (json.substring(0, json.length() - 1) + ",\"" + field + "\":\""
                + value + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static String sixDigitInstant(Instant instant) {
        Instant micros = instant.truncatedTo(ChronoUnit.MICROS);
        long seconds = micros.getEpochSecond();
        int micro = micros.getNano() / 1_000;
        return Instant.ofEpochSecond(seconds).toString().replace("Z", "")
                .replaceFirst("(?:\\.\\d+)?$", String.format(Locale.ROOT, ".%06d", micro)) + "Z";
    }

    private static TicketOutboxPublisherProperties producerProperties(String nameServer) {
        TicketOutboxPublisherProperties properties = new TicketOutboxPublisherProperties();
        properties.setEnabled(true);
        properties.setNameServer(nameServer);
        properties.setAccessKey(requiredEnvironment("FLOWDESK_TICKET_ROCKETMQ_ACCESS_KEY"));
        properties.setSecretKey(requiredEnvironment("FLOWDESK_TICKET_ROCKETMQ_SECRET_KEY"));
        return properties;
    }

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

    private DefaultMQPullConsumer pullConsumer(String group, RPCHook hook, String suffix) {
        DefaultMQPullConsumer consumer = new DefaultMQPullConsumer(group, hook);
        consumer.setNamesrvAddr(nameServer);
        consumer.setInstanceName("flowdesk-m5-" + suffix + "-" + UUID.randomUUID());
        consumer.setVipChannelEnabled(false);
        return consumer;
    }

    private RPCHook ticketHook() {
        return hook("FLOWDESK_TICKET_ROCKETMQ_ACCESS_KEY",
                "FLOWDESK_TICKET_ROCKETMQ_SECRET_KEY");
    }

    private RPCHook workflowHook() {
        return hook("FLOWDESK_WORKFLOW_ROCKETMQ_ACCESS_KEY",
                "FLOWDESK_WORKFLOW_ROCKETMQ_SECRET_KEY");
    }

    private RPCHook adminHook() {
        return hook("FLOWDESK_ROCKETMQ_ADMIN_ACCESS_KEY",
                "FLOWDESK_ROCKETMQ_ADMIN_SECRET_KEY");
    }

    private static RPCHook hook(String accessName, String secretName) {
        return new AclClientRPCHook(new SessionCredentials(
                requiredEnvironment(accessName), requiredEnvironment(secretName)));
    }

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

    private static UUID id(String scenario, TenantFixture tenant, String kind) {
        return UUID.nameUUIDFromBytes(("flowdesk:" + scenario + ":" + tenant.label()
                + ":" + kind).getBytes(StandardCharsets.UTF_8));
    }

    private static String ticketNumber(ScenarioFixture fixture) {
        return "M5A-" + fixture.scenario().replace("M5-", "") + "-"
                + fixture.tenant().label() + "-"
                + fixture.ticketId().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }

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

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required acceptance environment " + name);
        }
        return value.trim();
    }

    private static void requireExactEnvironment(String name, String expected) {
        if (!expected.equals(requiredEnvironment(name))) {
            throw new IllegalArgumentException(name + " must be exactly " + expected);
        }
    }

    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static UUID binaryUuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Timestamp nullableTimestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Object nullValue() {
        return "NULL";
    }

    private record TenantFixture(
            String label,
            UUID tenantId,
            UUID definitionId,
            UUID draftId,
            UUID versionId,
            UUID catalogId) {
    }

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

        private ScenarioFixture {
            body = body.clone();
            bodyHash = bodyHash.clone();
            businessIntentKey = businessIntentKey.clone();
            publicationKey = publicationKey.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public byte[] bodyHash() {
            return bodyHash.clone();
        }

        @Override
        public byte[] businessIntentKey() {
            return businessIntentKey.clone();
        }

        @Override
        public byte[] publicationKey() {
            return publicationKey.clone();
        }

        private ScenarioFixture withBody(byte[] replacement) {
            return new ScenarioFixture(scenario, tenant, ticketId, outboxId, messageId,
                    correlationId, replacement, sha256(replacement), businessIntentKey,
                    publicationKey);
        }
    }

    private record Wire(String keys, byte[] body, Map<String, String> properties) {

        private Wire {
            body = body.clone();
            properties = Map.copyOf(properties);
        }

        private Wire withBody(byte[] replacement) {
            Map<String, String> changed = new LinkedHashMap<>(properties);
            changed.put("fd_body_sha256", HEX.formatHex(sha256(replacement)));
            return new Wire(keys, replacement, changed);
        }

        private Wire withMessageId(UUID replacement) {
            Map<String, String> changed = new LinkedHashMap<>(properties);
            changed.put("fd_message_id", replacement.toString());
            return new Wire(replacement.toString(), body, changed);
        }

        private Wire withProperty(String name, String value) {
            Map<String, String> changed = new LinkedHashMap<>(properties);
            changed.put(name, value);
            return new Wire(keys, body, changed);
        }

        private Message message() {
            Message message = new Message(WorkflowStartContract.TOPIC, body.clone());
            message.setKeys(keys);
            properties.forEach(message::putUserProperty);
            return message;
        }

        private TicketOutboxMessage outboxMessage() {
            return new TicketOutboxMessage(
                    WorkflowStartContract.TOPIC, keys, body, properties);
        }

        private WorkflowStartTransportMessage dlqTransport() {
            return new WorkflowStartTransportMessage(
                    WorkflowStartContract.DLQ_TOPIC,
                    WorkflowStartContract.DLQ_ACCEPTOR_GROUP,
                    keys, null, body, properties);
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant.truncatedTo(ChronoUnit.MICROS);
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneId.of("UTC").equals(zone)) {
                throw new IllegalArgumentException("Acceptance clock is fixed to UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class AcceptanceEligibility
            implements WorkflowApproverEligibilityChecker {

        private final Map<UUID, CyclicBarrier> barriers = new ConcurrentHashMap<>();

        private void installBarrier(UUID ticketId, int parties) {
            barriers.put(ticketId, new CyclicBarrier(parties,
                    () -> barriers.remove(ticketId)));
        }

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

    @FunctionalInterface
    private interface CheckedOperation {
        void run() throws Exception;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
