package com.flowdesk.ticket.reverse;

import com.flowdesk.workflow.service.impl.WorkflowOutboxServiceImpl;

import com.flowdesk.workflow.event.WorkflowReverseEventCodec;
import com.flowdesk.workflow.event.WorkflowReverseEventContract;
import com.flowdesk.workflow.outbox.RocketMqWorkflowOutboxTransport;
import com.flowdesk.workflow.outbox.WorkflowOutboxMessage;
import com.flowdesk.workflow.outbox.WorkflowOutboxPublisher;
import com.flowdesk.workflow.outbox.WorkflowOutboxPublisherProperties;
import com.flowdesk.workflow.service.WorkflowOutboxService;
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
 * 里程碑六真实基础设施验收，核验反向事件的发布身份、重试次数与消费死信边界。
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
    private WorkflowOutboxService outboxRepository;
    private JdbcTransactionManager workflowTransactions;
    private RocketMqWorkflowOutboxTransport workflowTransport;
    private DefaultMQAdminExt mqAdmin;

    /**
     * 检查验收环境并启动隔离数据库及真实消息代理协作组件，准备完整故障场景。
     */
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
        workflowTransactions = new JdbcTransactionManager(runtimeDataSource);
        outboxRepository = new WorkflowOutboxServiceImpl(com.flowdesk.workflow.support.WorkflowTestMappers.mapper(runtimeDataSource, com.flowdesk.workflow.outbox.WorkflowOutboxMapper.class), workflowTransactions);

        workflowTransport = RocketMqWorkflowOutboxTransport.create(
                producerProperties(nameServer));
        mqAdmin = new DefaultMQAdminExt(adminHook(), 5_000L);
        mqAdmin.setAdminExtGroup("flowdesk_m6_acceptance_admin");
        mqAdmin.setNamesrvAddr(nameServer);
        mqAdmin.setInstanceName("flowdesk-m6-acceptance-admin-" + runId);
        mqAdmin.start();
        workflowTransport.start();
    }

    /**
     * 关闭验收组件并输出事实证据，即使单个清理失败仍继续释放剩余资源。
     */
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

    /**
     * 验证工作流 Outbox 使用真实代理并恰好在八次未确认发送后停止，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    @Order(1)
    void workflowOutboxUsesRealBrokerAndStopsAfterExactlyEightUnconfirmedSends()
            throws Exception {
        OutboxFixture published = insertOutbox("published", true);
        WorkflowOutboxPublisher livePublisher = new WorkflowOutboxPublisher(
                outboxRepository, workflowTransport);
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
                    outboxRepository, unavailable);
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

    /**
     * 验证真实主队列与死信接纳绑定均遵循一次首投和五次重投边界，并检查相关业务事实或错误响应符合约定。
     */
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

    /**
     * 拦截单次代理投递并保存观察到的原始身份，供重试和死信边界断言。
     */
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

    /**
     * 断言当前场景的观察到的投递身份及次数，失败时直接报告对应证据差异。
     */
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

    /**
     * 创建真实传输探针，使用稳定消息身份观察冻结绑定的路由及重投行为。
     */
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

    /**
     * 按冻结事件构建完整信封属性，使真实代理投递保持可验证身份。
     */
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

    /**
     * 建立当前测试场景的持久化前置事实，保留指定租户、身份和状态供后续业务路径验证。
     */
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

    /**
     * 直接读取指定 Outbox 行，供测试核对最终状态、次数和租约信息。
     */
    private Map<String, Object> outboxRow(UUID outboxId) {
        return workflowAdmin.queryForMap("""
                SELECT status,attempt_count,broker_confirmed_at,
                       LOWER(HEX(body_sha256)) AS stored_hash,
                       LOWER(SHA2(body_bytes,256)) AS actual_hash
                FROM flowdesk_workflow.outbox_event
                WHERE tenant_id=? AND outbox_id=?
                """, bytes(TENANT), bytes(outboxId));
    }

    /**
     * 在超时前等待指定原身份消息到达代理，超时以场景上下文使测试失败。
     */
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

    /**
     * 读取或统计当前场景的持久化及代理事实，为身份、状态和唯一副作用断言提供依据。
     */
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

    /**
     * 断言当前场景的代理消息身份与原始字节，失败时直接报告对应证据差异。
     */
    private static void assertBrokerIdentity(MessageExt message, UUID messageId,
                                             byte[] body, byte[] hash) {
        assertThat(message.getKeys()).isEqualTo(messageId.toString());
        assertThat(message.getBody()).containsExactly(body);
        assertThat(sha256(message.getBody())).containsExactly(hash);
        assertThat(message.getProperty("fd_message_id")).isEqualTo(messageId.toString());
        assertThat(message.getProperty("fd_body_sha256")).isEqualTo(HEX.formatHex(hash));
    }

    /**
     * 构造指定代理端点的生产者配置，供正常和不可用端点故障场景复用。
     */
    private WorkflowOutboxPublisherProperties producerProperties(String server) {
        WorkflowOutboxPublisherProperties properties = new WorkflowOutboxPublisherProperties();
        properties.setEnabled(true);
        properties.setNameServer(server);
        properties.setAccessKey(requiredEnvironment("FLOWDESK_WORKFLOW_ROCKETMQ_ACCESS_KEY"));
        properties.setSecretKey(requiredEnvironment("FLOWDESK_WORKFLOW_ROCKETMQ_SECRET_KEY"));
        return properties;
    }

    /**
     * 构造消息代理管理员测试身份，供查询队列和验收证据读取。
     */
    private RPCHook adminHook() {
        return hook("FLOWDESK_ROCKETMQ_ADMIN_ACCESS_KEY",
                "FLOWDESK_ROCKETMQ_ADMIN_SECRET_KEY");
    }

    /**
     * 构造工单发布测试身份，供 ACL 正常及越权场景验证。
     */
    private RPCHook ticketHook() {
        return hook("FLOWDESK_TICKET_ROCKETMQ_ACCESS_KEY",
                "FLOWDESK_TICKET_ROCKETMQ_SECRET_KEY");
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
    private static DriverManagerDataSource dataSource(String url, String username,
                                                      String password) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url);
        source.setUsername(username);
        source.setPassword(password);
        return source;
    }

    /**
     * 在给定时间内轮询条件，超时抛出带场景信息的断言错误。
     */
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

    /**
     * 将 UUID 编码为数据库使用的二进制值，空标识保持为空。
     */
    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    /**
     * 计算输入字节的 SHA-256 摘要，用于内容一致性核对及稳定业务键派生。
     */
    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * 读取必填验收环境变量，缺失时立即失败以避免误用默认外部资源。
     */
    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + name);
        }
        return value.trim();
    }

    /**
     * 断言验收环境变量与冻结值一致，防止在错误基础设施上执行场景。
     */
    private static void requireExactEnvironment(String name, String expected) {
        assertThat(requiredEnvironment(name)).isEqualTo(expected);
    }

    /**
     * 冻结测试样例或传输快照，保留原始身份、字节和摘要供正常及错误变体场景复用。
     */
    private record OutboxFixture(String label, UUID outboxId, UUID messageId,
                                 byte[] body, byte[] bodyHash) {
        /**
         * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
         */
        private OutboxFixture {
            body = body.clone();
            bodyHash = bodyHash.clone();
        }
        /**
         * 返回消息体字节的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override public byte[] body() { return body.clone(); }
        /**
         * 返回样例消息体摘要的副本，防止错误变体修改原始测试基线。
         */
        @Override public byte[] bodyHash() { return bodyHash.clone(); }
    }

    /**
     * 冻结测试样例或传输快照，保留原始身份、字节和摘要供正常及错误变体场景复用。
     */
    private record Probe(WorkflowReverseContract.Binding binding, UUID messageId,
                         byte[] body, byte[] bodyHash, WorkflowOutboxMessage message) {
        /**
         * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
         */
        private Probe {
            body = body.clone();
            bodyHash = bodyHash.clone();
        }
        /**
         * 返回消息体字节的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override public byte[] body() { return body.clone(); }
        /**
         * 返回样例消息体摘要的副本，防止错误变体修改原始测试基线。
         */
        @Override public byte[] bodyHash() { return bodyHash.clone(); }
    }
}
