package com.flowdesk.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.main.web-application-type=none",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:workflow-contract;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:schema-test.sql"
        }
)
class WorkflowDatabaseContractTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DEFINITION_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID DRAFT_ID = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID VERSION_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID APPROVER_1 = UUID.fromString("30000000-0000-0000-0000-000000000004");
    private static final UUID APPROVER_2 = UUID.fromString("30000000-0000-0000-0000-000000000005");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @DynamicPropertySource
    static void jwtVerificationKey(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.security.jwt.public-key-pem",
                () -> readResource("test-jwt-public-key.pem"));
    }

    @BeforeEach
    void cleanDatabase() {
        for (String table : List.of("message_recovery", "audit_record", "inbox_message",
                "outbox_event", "approval_task", "workflow_instance", "workflow_version",
                "workflow_draft", "workflow_definition")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void recoverableInboxAndRecoveryExposeFrozenColumnsConstraintsAndIndexes() throws Exception {
        assertThat(columnNames("inbox_message")).contains(
                "body_bytes", "body_sha256", "correlation_id", "causation_id", "occurred_at");
        assertThat(nullable("inbox_message", "body_bytes")).isFalse();
        assertThat(nullable("inbox_message", "correlation_id")).isFalse();
        assertThat(nullable("inbox_message", "causation_id")).isTrue();
        assertThat(nullable("inbox_message", "occurred_at")).isFalse();

        assertThat(columnNames("message_recovery")).contains("last_replay_correlation_id");
        assertThat(nullable("message_recovery", "last_replay_correlation_id")).isTrue();

        assertThat(constraintNames("inbox_message")).contains(
                "uk_wf_27_producer_message", "uk_wf_28_producer_intent",
                "ck_wf_inbox_state_result", "ck_wf_inbox_attempt_times");
        assertThat(constraintNames("message_recovery")).contains(
                "uk_wf_36_recovery_source", "ck_wf_recovery_state_times",
                "ck_wf_recovery_replay_correlation");
        assertThat(indexNames("inbox_message")).contains(
                "idx_wf_29_inbox_recovery", "idx_wf_30_inbox_result");
        assertThat(indexNames("message_recovery")).contains(
                "idx_wf_37_recovery_queue", "idx_wf_38_dead_letter");
    }

    @Test
    void inboxRequiresRecoverableEnvelopeFactsAndTenantScopedDuplicateKeys() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID messageId = UUID.randomUUID();
        byte[] intent = bytes(32, (byte) 0x11);
        insertProcessedInbox(TENANT_A, UUID.randomUUID(), messageId, intent,
                UUID.randomUUID(), now);

        assertThatThrownBy(() -> insertProcessedInbox(TENANT_A, UUID.randomUUID(), messageId,
                bytes(32, (byte) 0x12), UUID.randomUUID(), now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertProcessedInbox(TENANT_A, UUID.randomUUID(), UUID.randomUUID(),
                intent, UUID.randomUUID(), now))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Both duplicate scopes are tenant-local.
        insertProcessedInbox(TENANT_B, UUID.randomUUID(), messageId, intent,
                UUID.randomUUID(), now);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message", Integer.class))
                .isEqualTo(2);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO inbox_message
                  (tenant_id, inbox_id, producer_service, message_id, business_intent_key,
                   contract_ref, contract_version, body_bytes, body_sha256, correlation_id,
                   occurred_at, status, result_type, result_id, attempt_count,
                   first_received_at, last_attempt_at, processed_at)
                VALUES (?, ?, 'flowdesk-ticket-service', ?, ?, 'flowdesk.workflow.start.v1', 1,
                        NULL, ?, ?, ?, 'PROCESSED', 'WORKFLOW_INSTANCE', ?, 1, ?, ?, ?)
                """, uuid(TENANT_A), uuid(UUID.randomUUID()), uuid(UUID.randomUUID()),
                bytes(32, (byte) 0x23),
                bytes(32, (byte) 0x22), uuid(UUID.randomUUID()), Timestamp.from(now),
                uuid(UUID.randomUUID()), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO inbox_message
                  (tenant_id, inbox_id, producer_service, message_id, business_intent_key,
                   contract_ref, contract_version, body_bytes, body_sha256, correlation_id,
                   occurred_at, status, attempt_count, first_received_at, last_attempt_at)
                VALUES (?, ?, 'flowdesk-ticket-service', ?, ?, 'flowdesk.workflow.start.v1', 1,
                        ?, ?, ?, ?, 'PROCESSED', 1, ?, ?)
                """, uuid(TENANT_A), uuid(UUID.randomUUID()), uuid(UUID.randomUUID()),
                bytes(32, (byte) 0x31), new byte[]{1}, bytes(32, (byte) 0x32),
                uuid(UUID.randomUUID()), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void recoveryRequiresReplayCorrelationAndKeepsOneLifecyclePerSource() {
        Instant requiredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID sourceId = UUID.randomUUID();
        UUID recoveryId = UUID.randomUUID();
        byte[] intent = bytes(32, (byte) 0x41);
        insertOpenRecovery(TENANT_A, recoveryId, sourceId, intent, requiredAt);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE message_recovery
                SET state = 'REPLAYING', replay_count = 1, last_replayed_at = ?,
                    last_replayed_by_type = 'USER', last_replayed_by_id = ?,
                    last_replay_reason = 'retry after dependency recovery'
                WHERE tenant_id = ? AND recovery_id = ?
                """, Timestamp.from(requiredAt.plusSeconds(1)), uuid(APPROVER_1),
                uuid(TENANT_A), uuid(recoveryId)))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID replayCorrelation = UUID.randomUUID();
        assertThat(jdbc.update("""
                UPDATE message_recovery
                SET state = 'REPLAYING', replay_count = 1, last_replayed_at = ?,
                    last_replayed_by_type = 'USER', last_replayed_by_id = ?,
                    last_replay_reason = 'retry after dependency recovery',
                    last_replay_correlation_id = ?, lock_version = lock_version + 1
                WHERE tenant_id = ? AND recovery_id = ? AND state = 'OPEN' AND lock_version = 0
                """, Timestamp.from(requiredAt.plusSeconds(1)), uuid(APPROVER_1),
                uuid(replayCorrelation), uuid(TENANT_A), uuid(recoveryId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT last_replay_correlation_id FROM message_recovery
                WHERE tenant_id = ? AND recovery_id = ?
                """, byte[].class, uuid(TENANT_A), uuid(recoveryId)))
                .containsExactly(uuid(replayCorrelation));

        assertThatThrownBy(() -> insertOpenRecovery(
                TENANT_A, UUID.randomUUID(), sourceId, intent, requiredAt.plusSeconds(2)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO message_recovery
                  (tenant_id, recovery_id, direction, source_record_id, business_intent_key,
                   state, attempt_count, last_error_code, dead_lettered_at,
                   recovery_required_at, replay_count, last_replay_correlation_id)
                VALUES (?, ?, 'INBOX', ?, ?, 'OPEN', 6, 'WORKFLOW_DB_UNAVAILABLE', ?, ?, 0, ?)
                """, uuid(TENANT_A), uuid(UUID.randomUUID()), uuid(UUID.randomUUID()), intent,
                Timestamp.from(requiredAt.minusSeconds(1)), Timestamp.from(requiredAt),
                uuid(UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void workflowStartUniquenessAllowsOnlyOneInstanceAndPendingTask() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        seedPublishedVersion(TENANT_A, now);
        UUID ticketId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        byte[] startIntent = bytes(32, (byte) 0x51);
        insertRunningInstance(TENANT_A, instanceId, ticketId, startIntent, now);

        assertThatThrownBy(() -> insertRunningInstance(
                TENANT_A, UUID.randomUUID(), ticketId, bytes(32, (byte) 0x52), now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRunningInstance(
                TENANT_A, UUID.randomUUID(), UUID.randomUUID(), startIntent, now))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertPendingTask(TENANT_A, UUID.randomUUID(), instanceId, 1, APPROVER_1);
        assertThatThrownBy(() -> insertPendingTask(
                TENANT_A, UUID.randomUUID(), instanceId, 1, APPROVER_1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertPendingTask(
                TENANT_A, UUID.randomUUID(), instanceId, 2, APPROVER_2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertProcessedInbox(UUID tenantId, UUID inboxId, UUID messageId,
                                      byte[] businessIntent, UUID resultId, Instant now) {
        jdbc.update("""
                INSERT INTO inbox_message
                  (tenant_id, inbox_id, producer_service, message_id, business_intent_key,
                   contract_ref, contract_version, body_bytes, body_sha256, correlation_id,
                   causation_id, occurred_at, status, result_type, result_id, attempt_count,
                   first_received_at, last_attempt_at, processed_at)
                VALUES (?, ?, 'flowdesk-ticket-service', ?, ?, 'flowdesk.workflow.start.v1', 1,
                        ?, ?, ?, NULL, ?, 'PROCESSED', 'WORKFLOW_INSTANCE', ?, 1, ?, ?, ?)
                """, uuid(tenantId), uuid(inboxId), uuid(messageId), businessIntent,
                new byte[]{'{', '}'}, bytes(32, (byte) 0x21), uuid(UUID.randomUUID()),
                Timestamp.from(now), uuid(resultId), Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now));
    }

    private void insertOpenRecovery(UUID tenantId, UUID recoveryId, UUID sourceId,
                                    byte[] businessIntent, Instant requiredAt) {
        jdbc.update("""
                INSERT INTO message_recovery
                  (tenant_id, recovery_id, direction, source_record_id, business_intent_key,
                   state, attempt_count, next_attempt_at, last_error_code, last_error_summary,
                   dead_lettered_at, recovery_required_at)
                VALUES (?, ?, 'INBOX', ?, ?, 'OPEN', 6, NULL, 'WORKFLOW_DB_UNAVAILABLE',
                        'database dependency exhausted broker retries', ?, ?)
                """, uuid(tenantId), uuid(recoveryId), uuid(sourceId), businessIntent,
                Timestamp.from(requiredAt.minusSeconds(1)), Timestamp.from(requiredAt));
    }

    private void seedPublishedVersion(UUID tenantId, Instant now) {
        jdbc.update("""
                INSERT INTO workflow_definition
                  (tenant_id, definition_id, definition_code, definition_name, status,
                   created_by_type, updated_by_type)
                VALUES (?, ?, 'HIGH_RISK_APPROVAL', 'High risk approval', 'ACTIVE',
                        'SERVICE', 'SERVICE')
                """, uuid(tenantId), uuid(DEFINITION_ID));
        jdbc.update("""
                INSERT INTO workflow_draft
                  (tenant_id, draft_id, definition_id, status, condition_field_code,
                   condition_operator, condition_value_type, condition_value_text,
                   approval_level_count, level1_approver_user_id, level2_approver_user_id,
                   validation_state, validation_digest, validated_at, consumed_at, created_at,
                   created_by_type, updated_by_type)
                VALUES (?, ?, ?, 'CONSUMED', 'SERVICE_RISK_LEVEL', 'EQUALS', 'STRING', 'HIGH',
                        2, ?, ?, 'VALID', ?, ?, ?, ?, 'SERVICE', 'SERVICE')
                """, uuid(tenantId), uuid(DRAFT_ID), uuid(DEFINITION_ID), uuid(APPROVER_1),
                uuid(APPROVER_2), bytes(32, (byte) 0x61), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now.minusSeconds(1)));
        jdbc.update("""
                INSERT INTO workflow_version
                  (tenant_id, workflow_version_id, definition_id, version_no, source_draft_id,
                   status, condition_field_code, condition_operator, condition_value_type,
                   condition_value_text, approval_level_count, level1_approver_user_id,
                   level2_approver_user_id, snapshot_sha256, published_at, published_by_type)
                VALUES (?, ?, ?, 1, ?, 'PUBLISHED', 'SERVICE_RISK_LEVEL', 'EQUALS', 'STRING',
                        'HIGH', 2, ?, ?, ?, ?, 'SERVICE')
                """, uuid(tenantId), uuid(VERSION_ID), uuid(DEFINITION_ID), uuid(DRAFT_ID),
                uuid(APPROVER_1), uuid(APPROVER_2), bytes(32, (byte) 0x62), Timestamp.from(now));
    }

    private void insertRunningInstance(UUID tenantId, UUID instanceId, UUID ticketId,
                                       byte[] startIntent, Instant now) {
        jdbc.update("""
                INSERT INTO workflow_instance
                  (tenant_id, workflow_instance_id, ticket_id, start_intent_key, definition_id,
                   workflow_version_id, workflow_version_no, input_snapshot_json,
                   input_snapshot_sha256, condition_result, status, current_level, started_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, TRUE, 'RUNNING', 1, ?)
                """, uuid(tenantId), uuid(instanceId), uuid(ticketId), startIntent,
                uuid(DEFINITION_ID), uuid(VERSION_ID),
                "{\"schemaVersion\":\"1\",\"serviceRiskLevel\":\"HIGH\"}",
                bytes(32, (byte) 0x63), Timestamp.from(now));
    }

    private void insertPendingTask(UUID tenantId, UUID taskId, UUID instanceId,
                                   int level, UUID approverId) {
        jdbc.update("""
                INSERT INTO approval_task
                  (tenant_id, approval_task_id, workflow_instance_id, level_no,
                   approver_user_id, status)
                VALUES (?, ?, ?, ?, ?, 'PENDING')
                """, uuid(tenantId), uuid(taskId), uuid(instanceId), level, uuid(approverId));
    }

    private Set<String> columnNames(String table) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT LOWER(column_name) FROM information_schema.columns
                WHERE LOWER(table_name) = ?
                """, String.class, table));
    }

    private boolean nullable(String table, String column) {
        return "YES".equalsIgnoreCase(jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE LOWER(table_name) = ? AND LOWER(column_name) = ?
                """, String.class, table, column));
    }

    private Set<String> constraintNames(String table) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT LOWER(constraint_name) FROM information_schema.table_constraints
                WHERE LOWER(table_name) = ?
                """, String.class, table));
    }

    private Set<String> indexNames(String table) throws Exception {
        Set<String> names = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet indexes = connection.getMetaData().getIndexInfo(
                     null, null, table, false, false)) {
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                if (name != null) {
                    names.add(name.toLowerCase());
                }
            }
        }
        return names;
    }

    private static byte[] uuid(UUID value) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(value.getMostSignificantBits());
        buffer.putLong(value.getLeastSignificantBits());
        return buffer.array();
    }

    private static byte[] bytes(int size, byte value) {
        byte[] result = new byte[size];
        java.util.Arrays.fill(result, value);
        return result;
    }

    private static String readResource(String name) {
        try (var in = WorkflowDatabaseContractTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
