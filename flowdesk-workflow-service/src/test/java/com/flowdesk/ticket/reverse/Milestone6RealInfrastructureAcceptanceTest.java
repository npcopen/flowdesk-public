package com.flowdesk.ticket.reverse;

import com.flowdesk.workflow.event.WorkflowReverseEventCodec;
import com.flowdesk.workflow.event.WorkflowReverseEventContract;
import com.flowdesk.workflow.outbox.RocketMqWorkflowOutboxTransport;
import com.flowdesk.workflow.outbox.WorkflowOutboxMessage;
import com.flowdesk.workflow.outbox.WorkflowOutboxPublisher;
import com.flowdesk.workflow.outbox.WorkflowOutboxPublisherProperties;
import com.flowdesk.workflow.outbox.WorkflowOutboxRepository;
import com.flowdesk.workflow.service.WorkflowIdempotencyCodec;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.QueryResult;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.remoting.RPCHook;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Destructive, opt-in proof of the M6 reverse path against isolated MySQL 8.4 and
 * RocketMQ 5.3.4. Ordinary reactor builds skip this class; the formal M6 gate runs it
 * after provisioning exact ACLs and validates the separate sanitized evidence file.
 */
@EnabledIfEnvironmentVariable(named = "FLOWDESK_M6_REAL_INFRA", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(value = 18, unit = TimeUnit.MINUTES)
class Milestone6RealInfrastructureAcceptanceTest {
    private static final UUID TENANT = UUID.fromString(
            "12d7dca8-380c-5a6c-84ae-953502959486");
    private static final HexFormat HEX = HexFormat.of();

    private final List<Map<String, Object>> evidence = new ArrayList<>();
    private final Map<String, AtomicInteger> mainDeliveries = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> acceptorDeliveries = new ConcurrentHashMap<>();
    private final Map<String, List<WorkflowReverseTransportMessage>> mainObserved =
            new ConcurrentHashMap<>();
    private final Map<String, List<WorkflowReverseTransportMessage>> acceptorObserved =
            new ConcurrentHashMap<>();

    private String runId;
    private String nameServer;
    private JdbcTemplate workflowAdmin;
    private WorkflowOutboxRepository outboxRepository;
    private JdbcTransactionManager workflowTransactions;
    private RocketMqWorkflowOutboxTransport workflowTransport;
    private DefaultMQAdminExt mqAdmin;

    @BeforeAll
    void startIsolatedAcceptance() throws Exception {
        requireExactEnvironment("FLOWDESK_M6_ACCEPTANCE_ISOLATED", "true");
        runId = requiredEnvironment("FLOWDESK_M6_ACCEPTANCE_RUN_ID");
        if (!runId.matches("[a-z0-9][a-z0-9-]{5,47}")) {
            throw new IllegalArgumentException("Invalid isolated M6 acceptance run identifier");
        }
        nameServer = requiredEnvironment("FLOWDESK_ROCKETMQ_NAME_SERVER");

        DriverManagerDataSource adminDataSource = dataSource(
                requiredEnvironment("FLOWDESK_M6_WORKFLOW_ADMIN_DB_URL"), "root",
                requiredEnvironment("FLOWDESK_MYSQL_ROOT_PASSWORD"));
        DriverManagerDataSource runtimeDataSource = dataSource(
                requiredEnvironment("FLOWDESK_WORKFLOW_DB_URL"), "fd_workflow_runtime",
                requiredEnvironment("FLOWDESK_WORKFLOW_DB_PASSWORD"));
        workflowAdmin = new JdbcTemplate(adminDataSource);
        outboxRepository = new WorkflowOutboxRepository(new JdbcTemplate(runtimeDataSource));
        workflowTransactions = new JdbcTransactionManager(runtimeDataSource);

        workflowTransport = RocketMqWorkflowOutboxTransport.create(
                producerProperties(nameServer));
        mqAdmin = new DefaultMQAdminExt(adminHook(), 5_000L);
        mqAdmin.setAdminExtGroup("flowdesk_m6_acceptance_admin");
        mqAdmin.setNamesrvAddr(nameServer);
        mqAdmin.setInstanceName("flowdesk-m6-acceptance-admin-" + runId);
        mqAdmin.start();
        workflowTransport.start();
    }

    @AfterAll
    void stopAndWriteEvidence() throws Exception {
        try {
            if (workflowTransport != null) workflowTransport.close();
            if (mqAdmin != null) mqAdmin.shutdown();
        }
        finally {
            if (evidence.size() == 3) {
                Path result = Path.of(requiredEnvironment("FLOWDESK_M6_REAL_INFRA_RESULT"))
                        .toAbsolutePath().normalize();
                if (result.getParent() != null) Files.createDirectories(result.getParent());
                Map<String, Object> document = new LinkedHashMap<>();
                document.put("marker", "FLOWDESK_MILESTONE_6_REAL_INFRA_OK");
                document.put("runId", runId);
                document.put("brokerVersion", "5.3.4");
                document.put("scenarios", evidence);
                document.put("messageBodiesRecorded", false);
                document.put("credentialsRecorded", false);
                Files.writeString(result,
                        new ObjectMapper().writeValueAsString(document) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
            }
        }
    }

    @Test
    @Order(1)
    void workflowOutboxUsesRealBrokerAndStopsAfterExactlyEightUnconfirmedSends()
            throws Exception {
        OutboxFixture published = insertOutbox("published", true);
        WorkflowOutboxPublisher livePublisher = new WorkflowOutboxPublisher(
                outboxRepository, workflowTransport, workflowTransactions);
        assertThat(livePublisher.publishOnce().published()).isEqualTo(1);
        MessageExt stored = awaitMessage(WorkflowReverseEventContract.InstanceEstablished.TOPIC,
                published.messageId(), Duration.ofSeconds(60));
        assertBrokerIdentity(stored, published.messageId(), published.body(),
                published.bodyHash());
        Map<String, Object> publishedRow = outboxRow(published.outboxId());
        assertThat(publishedRow.get("status")).isEqualTo("PUBLISHED");
        assertThat(((Number) publishedRow.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(publishedRow.get("broker_confirmed_at")).isNotNull();

        OutboxFixture exhausted = insertOutbox("exhausted", false);
        try (RocketMqWorkflowOutboxTransport unavailable =
                     RocketMqWorkflowOutboxTransport.create(producerProperties("127.0.0.1:1"))) {
            unavailable.start();
            WorkflowOutboxPublisher boundary = new WorkflowOutboxPublisher(
                    outboxRepository, unavailable, workflowTransactions);
            List<Duration> backoffs = WorkflowReverseEventContract.FAILURE_BACKOFFS;
            for (int attempt = 1; attempt <= 8; attempt++) {
                WorkflowOutboxPublisher.PublishResult result = boundary.publishOnce();
                if (attempt < 8) {
                    assertThat(result.rescheduled()).isEqualTo(1);
                    long actualMicros = workflowAdmin.queryForObject("""
                            SELECT TIMESTAMPDIFF(MICROSECOND, updated_at, next_attempt_at)
                            FROM flowdesk_workflow.outbox_event
                            WHERE tenant_id=? AND outbox_id=?
                            """, Long.class, bytes(TENANT), bytes(exhausted.outboxId()));
                    assertThat(actualMicros).isEqualTo(backoffs.get(attempt - 1).toNanos() / 1_000);
                    workflowAdmin.update("""
                            UPDATE flowdesk_workflow.outbox_event
                            SET next_attempt_at=UTC_TIMESTAMP(6)
                            WHERE tenant_id=? AND outbox_id=?
                            """, bytes(TENANT), bytes(exhausted.outboxId()));
                }
                else {
                    assertThat(result.recovered()).isEqualTo(1);
                }
            }
            assertThat(boundary.publishOnce().scanned()).isZero();
        }

        Map<String, Object> exhaustedRow = outboxRow(exhausted.outboxId());
        assertThat(exhaustedRow.get("status")).isEqualTo("RECOVERY_REQUIRED");
        assertThat(((Number) exhaustedRow.get("attempt_count")).intValue()).isEqualTo(8);
        assertThat(exhaustedRow.get("broker_confirmed_at")).isNull();
        assertThat(exhaustedRow.get("stored_hash")).isEqualTo(HEX.formatHex(exhausted.bodyHash()));
        assertThat(exhaustedRow.get("actual_hash")).isEqualTo(HEX.formatHex(exhausted.bodyHash()));
        assertThat(workflowAdmin.queryForObject("""
                SELECT COUNT(*) FROM flowdesk_workflow.message_recovery
                WHERE tenant_id=? AND direction='OUTBOX' AND source_record_id=?
                  AND state='OPEN' AND attempt_count=8 AND dead_lettered_at IS NULL
                """, Integer.class, bytes(TENANT), bytes(exhausted.outboxId()))).isEqualTo(1);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("scenario", "M6-OUTBOX-BOUNDARY");
        row.put("publishedAttempts", 1);
        row.put("exhaustedAttempts", 8);
        row.put("automaticNinthSend", false);
        row.put("rawIdentityPreserved", true);
        row.put("backoffs", List.of("1s", "5s", "30s", "2m", "10m", "30m", "2h"));
        evidence.add(row);
    }

    @Test
    @Order(2)
    void bothRealBrokerBindingsHaveExactMainAndAcceptorOnePlusFiveBoundaries()
            throws Exception {
        List<Probe> probes = List.of(
                probe(WorkflowReverseContract.INSTANCE_ESTABLISHED),
                probe(WorkflowReverseContract.APPROVAL_FINALIZED));
        List<DefaultMQPushConsumer> mainConsumers = new ArrayList<>();
        try {
            for (Probe probe : probes) {
                DefaultMQPushConsumer consumer = WorkflowReverseRocketMqConsumers.createConsumer(
                        probe.binding().consumerGroup(), probe.binding().topic(), probe.binding(),
                        false, ticketHook(), nameServer, delivery -> observe(
                                probe, delivery, mainDeliveries, mainObserved));
                consumer.start();
                mainConsumers.add(consumer);
            }
            for (Probe probe : probes) {
                assertThat(workflowTransport.send(probe.message()).status().name())
                        .isEqualTo("SEND_OK");
            }
            for (Probe probe : probes) {
                MessageExt dead = awaitMessage(probe.binding().dlqTopic(), probe.messageId(),
                        Duration.ofMinutes(9));
                assertBrokerIdentity(dead, probe.messageId(), probe.body(), probe.bodyHash());
                assertThat(mainDeliveries.get(probe.messageId().toString()).get())
                        .isEqualTo(WorkflowReverseContract.DELIVERY_ATTEMPTS);
                assertObserved(mainObserved.get(probe.messageId().toString()), probe,
                        WorkflowReverseContract.DELIVERY_ATTEMPTS,
                        probe.binding().topic(), probe.binding().consumerGroup());
            }
        }
        finally {
            mainConsumers.forEach(DefaultMQPushConsumer::shutdown);
        }

        List<DefaultMQPushConsumer> acceptors = new ArrayList<>();
        try {
            for (Probe probe : probes) {
                DefaultMQPushConsumer consumer = WorkflowReverseRocketMqConsumers.createConsumer(
                        probe.binding().dlqAcceptorGroup(), probe.binding().dlqTopic(),
                        probe.binding(), true, ticketHook(), nameServer, delivery -> observe(
                                probe, delivery, acceptorDeliveries, acceptorObserved));
                consumer.start();
                acceptors.add(consumer);
            }
            for (Probe probe : probes) {
                String secondary = "%DLQ%" + probe.binding().dlqAcceptorGroup();
                MessageExt isolated = awaitMessage(secondary, probe.messageId(),
                        Duration.ofSeconds(90));
                assertBrokerIdentity(isolated, probe.messageId(), probe.body(), probe.bodyHash());
                int logicalAttempts = acceptorDeliveries.get(probe.messageId().toString()).get();
                assertThat(logicalAttempts).isEqualTo(WorkflowReverseContract.DELIVERY_ATTEMPTS);
                assertObserved(acceptorObserved.get(probe.messageId().toString()), probe,
                        WorkflowReverseContract.DELIVERY_ATTEMPTS,
                        probe.binding().dlqTopic(), probe.binding().dlqAcceptorGroup());

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("scenario", "M6-BROKER-" + probe.binding().kind().name());
                row.put("topic", probe.binding().topic());
                row.put("mainDeliveries", WorkflowReverseContract.DELIVERY_ATTEMPTS);
                row.put("acceptorDeliveries", WorkflowReverseContract.DELIVERY_ATTEMPTS);
                row.put("mainDlqIdentityPreserved", true);
                row.put("secondaryDlqIdentityPreserved", true);
                row.put("messageId", probe.messageId().toString());
                row.put("bodySha256", HEX.formatHex(probe.bodyHash()));
                evidence.add(row);
            }
        }
        finally {
            acceptors.forEach(DefaultMQPushConsumer::shutdown);
        }
    }

    private WorkflowReverseConsumeResult observe(
            Probe probe, WorkflowReverseTransportMessage delivery,
            Map<String, AtomicInteger> counts,
            Map<String, List<WorkflowReverseTransportMessage>> observed) {
        String id = delivery.userProperties().get("fd_message_id");
        if (!probe.messageId().toString().equals(id)) {
            return WorkflowReverseConsumeResult.ack("NON_PROBE_DRAINED");
        }
        counts.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
        observed.computeIfAbsent(id, ignored -> new CopyOnWriteArrayList<>()).add(delivery);
        return WorkflowReverseConsumeResult.retry("M6_ACCEPTANCE_INJECTED_FAILURE");
    }

    private static void assertObserved(List<WorkflowReverseTransportMessage> deliveries,
                                       Probe probe, int expected, String topic, String group) {
        assertThat(deliveries).hasSize(expected).allSatisfy(delivery -> {
            assertThat(delivery.topic()).isEqualTo(topic);
            assertThat(delivery.consumerGroup()).isEqualTo(group);
            assertThat(delivery.keys()).isEqualTo(probe.messageId().toString());
            assertThat(delivery.body()).containsExactly(probe.body());
            assertThat(delivery.userProperties()).containsEntry(
                    "fd_body_sha256", HEX.formatHex(probe.bodyHash()));
        });
    }

    private Probe probe(WorkflowReverseContract.Binding binding) {
        UUID messageId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        byte[] body = ("{\"schemaVersion\":\"1\",\"acceptanceProbe\":\""
                + binding.kind().name() + "\"}").getBytes(StandardCharsets.UTF_8);
        byte[] hash = sha256(body);
        Map<String, String> properties = envelopeProperties(binding, messageId, aggregateId, hash);
        return new Probe(binding, messageId, body, hash,
                new WorkflowOutboxMessage(binding.topic(), messageId.toString(), body, properties));
    }

    private static Map<String, String> envelopeProperties(
            WorkflowReverseContract.Binding binding, UUID messageId, UUID aggregateId,
            byte[] bodyHash) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("fd_envelope_version", "1");
        properties.put("fd_event_type", binding.eventType());
        properties.put("fd_contract_ref", binding.contractRef());
        properties.put("fd_contract_version", "1");
        properties.put("fd_producer", WorkflowReverseContract.PRODUCER);
        properties.put("fd_tenant_id", TENANT.toString());
        properties.put("fd_message_id", messageId.toString());
        properties.put("fd_business_intent_key", HEX.formatHex(sha256(bytes(messageId))));
        properties.put("fd_aggregate_type", WorkflowReverseContract.AGGREGATE_TYPE);
        properties.put("fd_aggregate_id", aggregateId.toString());
        properties.put("fd_correlation_id", UUID.randomUUID().toString());
        properties.put("fd_causation_id", UUID.randomUUID().toString());
        properties.put("fd_occurred_at", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        properties.put("fd_body_sha256", HEX.formatHex(bodyHash));
        return properties;
    }

    private OutboxFixture insertOutbox(String label, boolean established) {
        UUID outboxId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID causationId = UUID.randomUUID();
        Instant occurred = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MICROS);
        byte[] startIntent = WorkflowIdempotencyCodec.derivedKey(
                "FD-WORKFLOW-START-V1", TENANT, ticketId);
        byte[] body = established
                ? WorkflowReverseEventCodec.instanceEstablished(ticketId, instanceId, startIntent,
                        definitionId, versionId, 1, "RUNNING", occurred)
                : WorkflowReverseEventCodec.approvalFinalized(ticketId, instanceId, startIntent,
                        definitionId, versionId, 1, "PASSED", occurred);
        String contract = established
                ? WorkflowReverseEventContract.InstanceEstablished.CONTRACT_REF
                : WorkflowReverseEventContract.ApprovalFinalized.CONTRACT_REF;
        byte[] intent = established
                ? WorkflowReverseEventCodec.instanceEstablishedIntent(TENANT, ticketId)
                : WorkflowReverseEventCodec.approvalFinalizedIntent(TENANT, instanceId);
        byte[] hash = sha256(body);
        workflowAdmin.update("""
                INSERT INTO flowdesk_workflow.outbox_event
                  (tenant_id,outbox_id,message_id,publication_key,business_intent_key,
                   aggregate_type,aggregate_id,contract_ref,contract_version,body_bytes,
                   body_sha256,correlation_id,causation_id,status,attempt_count,next_attempt_at,
                   lock_version,created_at,created_by_type,updated_at,updated_by_type)
                VALUES (?,?,?,?,?,'WORKFLOW_INSTANCE',?,?,1,?,?,?,?, 'PENDING',0,?,0,?,
                        'SERVICE',?,'SERVICE')
                """, bytes(TENANT), bytes(outboxId), bytes(messageId),
                WorkflowIdempotencyCodec.publicationKey(TENANT, messageId), intent,
                bytes(instanceId), contract, body, hash, bytes(correlationId), bytes(causationId),
                Timestamp.from(occurred), Timestamp.from(occurred), Timestamp.from(occurred));
        return new OutboxFixture(label, outboxId, messageId, body, hash);
    }

    private Map<String, Object> outboxRow(UUID outboxId) {
        return workflowAdmin.queryForMap("""
                SELECT status,attempt_count,broker_confirmed_at,
                       LOWER(HEX(body_sha256)) AS stored_hash,
                       LOWER(SHA2(body_bytes,256)) AS actual_hash
                FROM flowdesk_workflow.outbox_event
                WHERE tenant_id=? AND outbox_id=?
                """, bytes(TENANT), bytes(outboxId));
    }

    private MessageExt awaitMessage(String topic, UUID messageId, Duration timeout) {
        MessageExt[] found = new MessageExt[1];
        await(timeout, () -> {
            List<MessageExt> messages = queryMessages(topic, messageId);
            if (messages.isEmpty()) return false;
            found[0] = messages.get(0);
            return true;
        }, "message " + messageId + " in " + topic);
        return found[0];
    }

    private List<MessageExt> queryMessages(String topic, UUID messageId) {
        try {
            QueryResult result = mqAdmin.queryMessage(topic, messageId.toString(), 32,
                    System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1),
                    System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1));
            if (result == null || result.getMessageList() == null) return List.of();
            return result.getMessageList().stream()
                    .filter(message -> messageId.toString().equals(message.getKeys())).toList();
        }
        catch (Exception notFoundYet) {
            String text = String.valueOf(notFoundYet.getMessage()).toLowerCase();
            if (text.contains("no message") || text.contains("not found")
                    || text.contains("query message by key")) return List.of();
            throw new IllegalStateException("Unable to query Broker topic " + topic, notFoundYet);
        }
    }

    private static void assertBrokerIdentity(MessageExt message, UUID messageId,
                                             byte[] body, byte[] hash) {
        assertThat(message.getKeys()).isEqualTo(messageId.toString());
        assertThat(message.getBody()).containsExactly(body);
        assertThat(sha256(message.getBody())).containsExactly(hash);
        assertThat(message.getProperty("fd_message_id")).isEqualTo(messageId.toString());
        assertThat(message.getProperty("fd_body_sha256")).isEqualTo(HEX.formatHex(hash));
    }

    private WorkflowOutboxPublisherProperties producerProperties(String server) {
        WorkflowOutboxPublisherProperties properties = new WorkflowOutboxPublisherProperties();
        properties.setEnabled(true);
        properties.setNameServer(server);
        properties.setAccessKey(requiredEnvironment("FLOWDESK_WORKFLOW_ROCKETMQ_ACCESS_KEY"));
        properties.setSecretKey(requiredEnvironment("FLOWDESK_WORKFLOW_ROCKETMQ_SECRET_KEY"));
        return properties;
    }

    private RPCHook adminHook() {
        return hook("FLOWDESK_ROCKETMQ_ADMIN_ACCESS_KEY",
                "FLOWDESK_ROCKETMQ_ADMIN_SECRET_KEY");
    }

    private RPCHook ticketHook() {
        return hook("FLOWDESK_TICKET_ROCKETMQ_ACCESS_KEY",
                "FLOWDESK_TICKET_ROCKETMQ_SECRET_KEY");
    }

    private static RPCHook hook(String accessName, String secretName) {
        return new AclClientRPCHook(new SessionCredentials(
                requiredEnvironment(accessName), requiredEnvironment(secretName)));
    }

    private static DriverManagerDataSource dataSource(String url, String username,
                                                      String password) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url);
        source.setUsername(username);
        source.setPassword(password);
        return source;
    }

    private static void await(Duration timeout, BooleanSupplier condition, String context) {
        Instant deadline = Instant.now().plus(timeout);
        Throwable last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                if (condition.getAsBoolean()) return;
            }
            catch (Throwable failure) {
                last = failure;
            }
            try {
                Thread.sleep(250);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + context,
                        interrupted);
            }
        }
        throw new IllegalStateException("Timed out waiting for " + context, last);
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + name);
        }
        return value.trim();
    }

    private static void requireExactEnvironment(String name, String expected) {
        assertThat(requiredEnvironment(name)).isEqualTo(expected);
    }

    private record OutboxFixture(String label, UUID outboxId, UUID messageId,
                                 byte[] body, byte[] bodyHash) {
        private OutboxFixture {
            body = body.clone();
            bodyHash = bodyHash.clone();
        }
        @Override public byte[] body() { return body.clone(); }
        @Override public byte[] bodyHash() { return bodyHash.clone(); }
    }

    private record Probe(WorkflowReverseContract.Binding binding, UUID messageId,
                         byte[] body, byte[] bodyHash, WorkflowOutboxMessage message) {
        private Probe {
            body = body.clone();
            bodyHash = bodyHash.clone();
        }
        @Override public byte[] body() { return body.clone(); }
        @Override public byte[] bodyHash() { return bodyHash.clone(); }
    }
}
