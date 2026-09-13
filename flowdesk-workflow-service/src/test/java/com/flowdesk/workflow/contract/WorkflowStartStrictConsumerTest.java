package com.flowdesk.workflow.contract;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.RoleCode;
import com.flowdesk.common.security.JwtPrincipal;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.tenant.TenantContextSource;
import com.flowdesk.common.web.JwtPrincipalHolder;
import com.flowdesk.workflow.start.WorkflowApproverEligibilityChecker;
import com.flowdesk.workflow.start.WorkflowIamUnavailableException;
import com.flowdesk.workflow.start.WorkflowRecoveryReplayService;
import com.flowdesk.workflow.start.WorkflowStartContract;
import com.flowdesk.workflow.start.WorkflowStartConsumeResult;
import com.flowdesk.workflow.start.WorkflowStartConsumer;
import com.flowdesk.workflow.start.WorkflowStartDlqAcceptor;
import com.flowdesk.workflow.start.WorkflowStartTransportMessage;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.main.web-application-type=none",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:workflow-start-entry;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:schema-test.sql",
                "flowdesk.workflow.start.entry.enabled=true"
        })
@Import(WorkflowStartStrictConsumerTest.EligibilityStubConfiguration.class)
class WorkflowStartStrictConsumerTest {

    private static final UUID TENANT_A =
            UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db101");
    private static final UUID TICKET_ID =
            UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db102");
    private static final UUID DEFINITION_A =
            UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db103");
    private static final UUID VERSION_A =
            UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db104");
    private static final UUID APPROVER_A1 =
            UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db201");
    private static final UUID APPROVER_A2 =
            UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db202");
    private static final UUID ADMIN_A =
            UUID.fromString("018f6f2d-9b57-7c31-a69f-4db0d84db299");
    private static final UUID TENANT_B =
            UUID.fromString("028f6f2d-9b57-7c31-a69f-4db0d84db101");
    private static final UUID DEFINITION_B =
            UUID.fromString("028f6f2d-9b57-7c31-a69f-4db0d84db103");
    private static final UUID VERSION_B =
            UUID.fromString("028f6f2d-9b57-7c31-a69f-4db0d84db104");
    private static final String EXACT_BODY =
            "{\"requesterUserId\":\"018f6f2d-9b57-7c31-a69f-4db0d84db105\""
                    + ",\"schemaVersion\":\"1\",\"serviceRiskLevel\":\"HIGH\""
                    + ",\"ticketId\":\"018f6f2d-9b57-7c31-a69f-4db0d84db102\""
                    + ",\"versionNo\":3"
                    + ",\"workflowDefinitionId\":\"018f6f2d-9b57-7c31-a69f-4db0d84db103\""
                    + ",\"workflowVersionId\":\"018f6f2d-9b57-7c31-a69f-4db0d84db104\"}";

    @Autowired
    private WorkflowStartConsumer consumer;

    @Autowired
    private WorkflowStartDlqAcceptor dlqAcceptor;

    @Autowired
    private WorkflowRecoveryReplayService recoveryReplayService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TrackingEligibilityChecker eligibility;

    @DynamicPropertySource
    static void jwtVerificationKey(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.security.jwt.public-key-pem",
                () -> readResource("test-jwt-public-key.pem"));
    }

    @BeforeEach
    void resetDatabaseAndDependencies() {
        for (String table : List.of("message_recovery", "audit_record", "inbox_message",
                "outbox_event", "approval_task", "workflow_instance", "workflow_version",
                "workflow_draft", "workflow_definition")) {
            jdbc.update("DELETE FROM " + table);
        }
        eligibility.reset();
        seedPublishedVersion(
                TENANT_A, DEFINITION_A, VERSION_A, APPROVER_A1, APPROVER_A2, "HIGH");
    }

    @Test
    void commitsInboxInstanceFirstTaskAndAuditBeforeAck() {
        WorkflowStartConsumeResult result = consume(WorkflowStartV1WireFixture.load());

        assertThat(result.disposition())
                .isEqualTo(WorkflowStartConsumeResult.BrokerDisposition.ACK);
        assertThat(result.code()).isEqualTo("WORKFLOW_START_PROCESSED");
        assertThat(result.workflowInstanceId()).isNotNull();
        assertThat(count("inbox_message")).isEqualTo(1);
        assertThat(count("workflow_instance")).isEqualTo(1);
        assertThat(count("approval_task")).isEqualTo(1);
        assertThat(count("audit_record")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE contract_ref=?", Integer.class,
                "flowdesk.workflow.instance-established.v1")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE contract_ref=?", Integer.class,
                "flowdesk.workflow.notification-requested.v1")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM inbox_message", String.class)).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM workflow_instance", String.class)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject(
                "SELECT current_level FROM workflow_instance", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT approver_user_id FROM approval_task", byte[].class))
                .containsExactly(uuid(APPROVER_A1));
        assertThat(eligibility.calls).isEqualTo(1);
        assertThat(eligibility.calledInsideTransaction).isFalse();
        assertThat(eligibility.contextSource).isEqualTo(TenantContextSource.BROKER_MESSAGE);
        assertThat(eligibility.contextTenant).isEqualTo(TENANT_A);
        assertThat(TenantContextHolder.currentOrNull()).isNull();
    }

    @Test
    void exactMessageAndSameIntentRedeliveriesUseFastPathWithoutNewEffects() {
        WorkflowStartV1WireFixture.WireMessage original = WorkflowStartV1WireFixture.load();
        WorkflowStartConsumeResult first = consume(original);
        WorkflowStartConsumeResult sameMessage = consume(original);
        WorkflowStartConsumeResult sameIntent = consume(original.withMessageId(
                "018f6f2d-9b57-7c31-a69f-4db0d84db108"));

        assertThat(sameMessage.code()).isEqualTo("WORKFLOW_START_DUPLICATE");
        assertThat(sameIntent.code()).isEqualTo("WORKFLOW_START_DUPLICATE");
        assertThat(sameMessage.workflowInstanceId()).isEqualTo(first.workflowInstanceId());
        assertThat(sameIntent.workflowInstanceId()).isEqualTo(first.workflowInstanceId());
        assertThat(count("inbox_message")).isEqualTo(1);
        assertThat(count("workflow_instance")).isEqualTo(1);
        assertThat(count("approval_task")).isEqualTo(1);
        assertThat(count("audit_record")).isEqualTo(1);
        assertThat(eligibility.calls).isEqualTo(1);
    }

    @Test
    void sameMessageDifferentContentAddsOnlyConflictAuditAndNeverOverwritesInbox() {
        WorkflowStartV1WireFixture.WireMessage original = WorkflowStartV1WireFixture.load();
        consume(original);
        byte[] storedBody = jdbc.queryForObject(
                "SELECT body_bytes FROM inbox_message", byte[].class);
        byte[] storedHash = jdbc.queryForObject(
                "SELECT body_sha256 FROM inbox_message", byte[].class);
        byte[] changedBody = EXACT_BODY.replace(
                        "}", ",\"consumerFirstOptionalHint\":\"changed-message\"}")
                .getBytes(StandardCharsets.UTF_8);

        WorkflowStartConsumeResult conflict = consume(
                original.withBodyAndMatchingHash(changedBody));

        assertThat(conflict.disposition())
                .isEqualTo(WorkflowStartConsumeResult.BrokerDisposition.ACK);
        assertThat(conflict.code()).isEqualTo("MESSAGE_ID_CONTENT_CONFLICT");
        assertThat(count("inbox_message")).isEqualTo(1);
        assertThat(count("workflow_instance")).isEqualTo(1);
        assertThat(count("approval_task")).isEqualTo(1);
        assertThat(count("audit_record")).isEqualTo(2);
        assertThat(count("outbox_event")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT body_bytes FROM inbox_message", byte[].class)).containsExactly(storedBody);
        assertThat(jdbc.queryForObject(
                "SELECT body_sha256 FROM inbox_message", byte[].class)).containsExactly(storedHash);
        assertThat(jdbc.queryForObject("""
                SELECT error_code FROM audit_record
                WHERE action_code = 'WORKFLOW_START_CONFLICT'
                """, String.class)).isEqualTo("MESSAGE_ID_CONTENT_CONFLICT");
        assertThat(eligibility.calls).isEqualTo(1);
    }

    @Test
    void sameBusinessIntentDifferentContentUsesSecondFrozenConflictRule() {
        WorkflowStartV1WireFixture.WireMessage original = WorkflowStartV1WireFixture.load();
        consume(original);
        byte[] changedBody = EXACT_BODY.replace(
                        "}", ",\"consumerFirstOptionalHint\":\"changed-intent\"}")
                .getBytes(StandardCharsets.UTF_8);
        WorkflowStartV1WireFixture.WireMessage conflicting = original
                .withBodyAndMatchingHash(changedBody)
                .withMessageId("018f6f2d-9b57-7c31-a69f-4db0d84db108");

        WorkflowStartConsumeResult result = consume(conflicting);

        assertThat(result.code()).isEqualTo("BUSINESS_INTENT_CONTENT_CONFLICT");
        assertThat(count("inbox_message")).isEqualTo(1);
        assertThat(count("workflow_instance")).isEqualTo(1);
        assertThat(count("approval_task")).isEqualTo(1);
        assertThat(count("audit_record")).isEqualTo(2);
        assertThat(eligibility.calls).isEqualTo(1);
    }

    @Test
    void invalidTransportIsAckedBeforeTenantContextAndHasNoTenantSideEffects() {
        WorkflowStartV1WireFixture.WireMessage invalid = WorkflowStartV1WireFixture.load()
                .withProperty("fd_body_sha256", "0".repeat(64));

        WorkflowStartConsumeResult result = consume(invalid);

        assertThat(result.disposition())
                .isEqualTo(WorkflowStartConsumeResult.BrokerDisposition.ACK);
        assertThat(result.code()).isEqualTo("BODY_HASH_MISMATCH");
        assertThat(count("inbox_message")).isZero();
        assertThat(count("audit_record")).isZero();
        assertThat(eligibility.calls).isZero();
        assertThat(TenantContextHolder.currentOrNull()).isNull();
    }

    @Test
    void tamperedEnvelopeTenantFailsBeforeAnyTenantReadOrWrite() {
        WorkflowStartV1WireFixture.WireMessage invalid = WorkflowStartV1WireFixture.load()
                .withProperty("fd_tenant_id", TENANT_B.toString());

        WorkflowStartConsumeResult result = consume(invalid);

        assertThat(result.code()).isEqualTo("BUSINESS_INTENT_KEY_MISMATCH");
        assertThat(count("inbox_message")).isZero();
        assertThat(count("audit_record")).isZero();
        assertThat(eligibility.calls).isZero();
    }

    @Test
    void anotherTenantsRealVersionIsRejectedInTenantATransactionWithoutBusinessEffects() {
        seedPublishedVersion(
                TENANT_B, DEFINITION_B, VERSION_B,
                UUID.fromString("028f6f2d-9b57-7c31-a69f-4db0d84db201"),
                UUID.fromString("028f6f2d-9b57-7c31-a69f-4db0d84db202"), "HIGH");
        byte[] crossTenantBody = EXACT_BODY
                .replace(DEFINITION_A.toString(), DEFINITION_B.toString())
                .replace(VERSION_A.toString(), VERSION_B.toString())
                .getBytes(StandardCharsets.UTF_8);
        WorkflowStartV1WireFixture.WireMessage crossTenant = WorkflowStartV1WireFixture.load()
                .withBodyAndMatchingHash(crossTenantBody);

        WorkflowStartConsumeResult result = consume(crossTenant);

        assertThat(result.disposition())
                .isEqualTo(WorkflowStartConsumeResult.BrokerDisposition.ACK);
        assertThat(result.code()).isEqualTo("WORKFLOW_VERSION_REFERENCE_INVALID");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM inbox_message WHERE tenant_id = ?
                """, String.class, uuid(TENANT_A))).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM inbox_message WHERE tenant_id = ?
                """, Integer.class, uuid(TENANT_B))).isZero();
        assertThat(count("workflow_instance")).isZero();
        assertThat(count("approval_task")).isZero();
        assertThat(count("audit_record")).isEqualTo(1);
        assertThat(count("outbox_event")).isZero();
        assertThat(eligibility.calls).isZero();
    }

    @Test
    void iamNegativeDecisionReturnsBrokerRetryAndWritesNothing() {
        eligibility.eligible = false;

        WorkflowStartConsumeResult result = consume(WorkflowStartV1WireFixture.load());

        assertThat(result.disposition())
                .isEqualTo(WorkflowStartConsumeResult.BrokerDisposition.RETRY);
        assertThat(result.code()).isEqualTo("WORKFLOW_APPROVER_NOT_ELIGIBLE");
        assertThat(count("inbox_message")).isZero();
        assertThat(count("workflow_instance")).isZero();
        assertThat(count("approval_task")).isZero();
        assertThat(count("audit_record")).isZero();
        assertThat(eligibility.calls).isEqualTo(1);
    }

    @Test
    void temporaryIamFailureReturnsBrokerRetryAndWritesNothing() {
        eligibility.unavailable = true;

        WorkflowStartConsumeResult result = consume(WorkflowStartV1WireFixture.load());

        assertThat(result.disposition())
                .isEqualTo(WorkflowStartConsumeResult.BrokerDisposition.RETRY);
        assertThat(result.code()).isEqualTo("IAM_ELIGIBILITY_UNAVAILABLE");
        assertThat(count("inbox_message")).isZero();
        assertThat(count("workflow_instance")).isZero();
        assertThat(count("approval_task")).isZero();
        assertThat(count("audit_record")).isZero();
        assertThat(eligibility.calls).isEqualTo(1);
    }

    @Test
    void falseConditionCommitsCompletedInstanceWithoutIamOrTask() {
        jdbc.update("""
                UPDATE workflow_version SET condition_value_text = 'NORMAL'
                WHERE tenant_id = ? AND workflow_version_id = ?
                """, uuid(TENANT_A), uuid(VERSION_A));

        WorkflowStartConsumeResult result = consume(WorkflowStartV1WireFixture.load());

        assertThat(result.code()).isEqualTo("WORKFLOW_START_PROCESSED");
        assertThat(count("inbox_message")).isEqualTo(1);
        assertThat(count("workflow_instance")).isEqualTo(1);
        assertThat(count("approval_task")).isZero();
        assertThat(count("audit_record")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM workflow_instance", String.class))
                .isEqualTo("COMPLETED_PASSED");
        assertThat(jdbc.queryForObject(
                "SELECT condition_result FROM workflow_instance", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT completed_at IS NOT NULL FROM workflow_instance", Boolean.class)).isTrue();
        assertThat(eligibility.calls).isZero();
    }

    @Test
    void localTransactionRejectsAWorkflowVersionChangedAfterIamPrecheck() {
        eligibility.afterCheck = () -> jdbc.update("""
                UPDATE workflow_version SET condition_value_text = 'NORMAL'
                WHERE tenant_id = ? AND workflow_version_id = ?
                """, uuid(TENANT_A), uuid(VERSION_A));

        WorkflowStartConsumeResult result = consume(WorkflowStartV1WireFixture.load());

        assertThat(result.code()).isEqualTo("WORKFLOW_VERSION_CONTENT_CHANGED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM inbox_message", String.class)).isEqualTo("REJECTED");
        assertThat(count("workflow_instance")).isZero();
        assertThat(count("approval_task")).isZero();
        assertThat(count("audit_record")).isEqualTo(1);
        assertThat(eligibility.calls).isEqualTo(1);
    }

    @Test
    void failureOnFinalAuditRollsBackInboxInstanceAndTaskBeforeReturningRetry() {
        jdbc.execute("""
                ALTER TABLE audit_record
                ADD CONSTRAINT ck_test_audit_failure CHECK (FALSE)
                """);
        WorkflowStartConsumeResult result;
        try {
            result = consume(WorkflowStartV1WireFixture.load());
        }
        finally {
            jdbc.execute("""
                    ALTER TABLE audit_record DROP CONSTRAINT ck_test_audit_failure
                    """);
        }

        assertThat(result.disposition())
                .isEqualTo(WorkflowStartConsumeResult.BrokerDisposition.RETRY);
        assertThat(result.code()).isEqualTo("WORKFLOW_DB_UNAVAILABLE");
        assertThat(count("inbox_message")).isZero();
        assertThat(count("workflow_instance")).isZero();
        assertThat(count("approval_task")).isZero();
        assertThat(count("audit_record")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void dlqAcceptorAtomicallyPreservesSourceAndCreatesOneRecovery() {
        WorkflowStartV1WireFixture.WireMessage wire = WorkflowStartV1WireFixture.load();
        WorkflowStartConsumeResult first = dlqAcceptor.accept(dlqDelivery(wire));
        WorkflowStartConsumeResult duplicate = dlqAcceptor.accept(dlqDelivery(wire));

        assertThat(first.disposition()).isEqualTo(
                WorkflowStartConsumeResult.BrokerDisposition.ACK);
        assertThat(first.code()).isEqualTo("WORKFLOW_START_RECOVERY_REQUIRED");
        assertThat(duplicate.code()).isEqualTo("WORKFLOW_START_DLQ_RECOVERY_DUPLICATE");
        assertThat(count("inbox_message")).isEqualTo(1);
        assertThat(count("message_recovery")).isEqualTo(1);
        assertThat(count("audit_record")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM inbox_message", String.class))
                .isEqualTo("RECOVERY_REQUIRED");
        assertThat(jdbc.queryForObject(
                "SELECT attempt_count FROM inbox_message", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject(
                "SELECT body_bytes FROM inbox_message", byte[].class))
                .containsExactly(wire.body());
        assertThat(jdbc.queryForObject(
                "SELECT correlation_id FROM inbox_message", byte[].class))
                .containsExactly(uuid(UUID.fromString(
                        wire.properties().get("fd_correlation_id"))));
        assertThat(jdbc.queryForObject(
                "SELECT state FROM message_recovery", String.class)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject(
                "SELECT direction FROM message_recovery", String.class)).isEqualTo("INBOX");
        assertThat(jdbc.queryForObject(
                "SELECT dead_lettered_at IS NOT NULL FROM message_recovery", Boolean.class))
                .isTrue();
        assertThat(count("workflow_instance")).isZero();
        assertThat(count("approval_task")).isZero();
        assertThat(eligibility.calls).isZero();
    }

    @Test
    void deterministicDlqReferenceFailureIsRejectedWithoutRecovery() {
        jdbc.update("DELETE FROM workflow_version");

        WorkflowStartConsumeResult result = dlqAcceptor.accept(
                dlqDelivery(WorkflowStartV1WireFixture.load()));

        assertThat(result.disposition()).isEqualTo(
                WorkflowStartConsumeResult.BrokerDisposition.ACK);
        assertThat(result.code()).isEqualTo("WORKFLOW_VERSION_REFERENCE_INVALID");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM inbox_message", String.class)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject(
                "SELECT attempt_count FROM inbox_message", Integer.class)).isEqualTo(6);
        assertThat(count("message_recovery")).isZero();
        assertThat(count("workflow_instance")).isZero();
        assertThat(count("audit_record")).isEqualTo(1);
    }

    @Test
    void manualReplayUsesOriginalEnvelopeAndSameConsumerEntry() throws Exception {
        WorkflowStartV1WireFixture.WireMessage wire = WorkflowStartV1WireFixture.load();
        dlqAcceptor.accept(dlqDelivery(wire));
        UUID recoveryId = binaryUuid(jdbc.queryForObject(
                "SELECT recovery_id FROM message_recovery", byte[].class));
        byte[] originalBody = jdbc.queryForObject(
                "SELECT body_bytes FROM inbox_message", byte[].class);
        byte[] originalMessageId = jdbc.queryForObject(
                "SELECT message_id FROM inbox_message", byte[].class);
        byte[] originalCorrelation = jdbc.queryForObject(
                "SELECT correlation_id FROM inbox_message", byte[].class);
        Timestamp originalOccurredAt = jdbc.queryForObject(
                "SELECT occurred_at FROM inbox_message", Timestamp.class);

        JwtPrincipal principal = JwtPrincipal.human(
                TENANT_A, ADMIN_A, Set.of(RoleCode.SERVICE_DESK_ADMIN),
                Set.of(PermissionCode.RECOVERY_REPLAY_TENANT),
                Instant.now().minusSeconds(1), Instant.now().plusSeconds(60),
                UUID.randomUUID());
        JwtPrincipalHolder.set(principal);
        WorkflowRecoveryReplayService.ReplayResult accepted;
        try (TenantContextScope ignored = TenantContextScope.open(
                principal.toHumanTenantContext(UUID.randomUUID()))) {
            accepted = recoveryReplayService.replay(
                    recoveryId, "Retry after IAM recovery",
                    "workflow-replay-0001", "\"lv:0\"");
        }
        finally {
            JwtPrincipalHolder.clear();
        }

        assertThat(accepted.replayed()).isFalse();
        assertThat(accepted.recovery().state()).isEqualTo("REPLAYING");
        awaitRecoveryState("RESOLVED");
        awaitReplayAuditCount(2);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM inbox_message", String.class)).isEqualTo("PROCESSED");
        assertThat(count("workflow_instance")).isEqualTo(1);
        assertThat(count("approval_task")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT replay_count FROM message_recovery", Integer.class)).isEqualTo(1);
        byte[] attemptCorrelation = jdbc.queryForObject(
                "SELECT last_replay_correlation_id FROM message_recovery", byte[].class);
        assertThat(attemptCorrelation).isNotEqualTo(originalCorrelation);
        assertThat(jdbc.queryForObject(
                "SELECT body_bytes FROM inbox_message", byte[].class))
                .containsExactly(originalBody);
        assertThat(jdbc.queryForObject(
                "SELECT message_id FROM inbox_message", byte[].class))
                .containsExactly(originalMessageId);
        assertThat(jdbc.queryForObject(
                "SELECT correlation_id FROM inbox_message", byte[].class))
                .containsExactly(originalCorrelation);
        assertThat(jdbc.queryForObject(
                "SELECT occurred_at FROM inbox_message", Timestamp.class))
                .isEqualTo(originalOccurredAt);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_record
                WHERE correlation_id = ? AND action_code LIKE 'WORKFLOW_RECOVERY_REPLAY_%'
                """, Integer.class, attemptCorrelation)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_record
                WHERE correlation_id = ? AND action_code = 'WORKFLOW_START_CONSUMED'
                """, Integer.class, originalCorrelation)).isEqualTo(1);
        assertThat(TenantContextHolder.currentOrNull()).isNull();
    }

    @Test
    void manualWorkflowOutboxReplayQueuesTheOriginalFactAndIsIdempotent() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID outboxId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID recoveryId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        byte[] intent = bytes(32, (byte) 0x41);
        byte[] publication = bytes(32, (byte) 0x42);
        byte[] body = "{\"persisted\":true}".getBytes(StandardCharsets.UTF_8);
        byte[] bodyHash = bytes(32, (byte) 0x43);
        jdbc.update("""
                INSERT INTO outbox_event
                  (tenant_id,outbox_id,message_id,publication_key,business_intent_key,
                   aggregate_type,aggregate_id,contract_ref,contract_version,body_bytes,
                   body_sha256,correlation_id,causation_id,status,attempt_count,next_attempt_at,
                   last_error_code,lock_version,created_at,created_by_type,updated_at,updated_by_type)
                VALUES (?,?,?,?,?,'WORKFLOW_INSTANCE',?,
                        'flowdesk.workflow.instance-established.v1',1,?,?,?,?,
                        'RECOVERY_REQUIRED',8,?,'BROKER_UNAVAILABLE',9,?,
                        'SERVICE',?,'SERVICE')
                """, uuid(TENANT_A), uuid(outboxId), uuid(messageId), publication, intent,
                uuid(instanceId), body, bodyHash, uuid(UUID.randomUUID()), uuid(UUID.randomUUID()),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO message_recovery
                  (tenant_id,recovery_id,direction,source_record_id,business_intent_key,
                   state,attempt_count,last_error_code,recovery_required_at,replay_count,
                   lock_version,created_at,updated_at,updated_by_type)
                VALUES (?,?,'OUTBOX',?,?,'OPEN',8,'BROKER_UNAVAILABLE',?,0,0,?,?,'SERVICE')
                """, uuid(TENANT_A), uuid(recoveryId), uuid(outboxId), intent,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));

        JwtPrincipal principal = JwtPrincipal.human(
                TENANT_A, ADMIN_A, Set.of(RoleCode.SERVICE_DESK_ADMIN),
                Set.of(PermissionCode.RECOVERY_REPLAY_TENANT),
                now.minusSeconds(1), now.plusSeconds(60), UUID.randomUUID());
        JwtPrincipalHolder.set(principal);
        WorkflowRecoveryReplayService.ReplayResult first;
        WorkflowRecoveryReplayService.ReplayResult replayed;
        try (TenantContextScope ignored = TenantContextScope.open(
                principal.toHumanTenantContext(UUID.randomUUID()))) {
            first = recoveryReplayService.replay(recoveryId, "Retry original publication",
                    "workflow-outbox-replay-0001", "\"lv:0\"");
            replayed = recoveryReplayService.replay(recoveryId, "Retry original publication",
                    "workflow-outbox-replay-0001", "\"lv:0\"");
        }
        finally {
            JwtPrincipalHolder.clear();
        }

        assertThat(first.replayed()).isFalse();
        assertThat(first.recovery().direction()).isEqualTo("OUTBOX");
        assertThat(first.recovery().state()).isEqualTo("REPLAYING");
        assertThat(replayed.replayed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event", String.class))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM outbox_event", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT message_id FROM outbox_event", byte[].class))
                .containsExactly(uuid(messageId));
        assertThat(jdbc.queryForObject("SELECT body_bytes FROM outbox_event", byte[].class))
                .containsExactly(body);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_record WHERE action_code="
                + "'WORKFLOW_RECOVERY_REPLAY_REQUESTED'", Integer.class)).isEqualTo(1);
    }

    private WorkflowStartConsumeResult consume(WorkflowStartV1WireFixture.WireMessage message) {
        return consumer.consume(message.transportMessage());
    }

    private WorkflowStartTransportMessage dlqDelivery(
            WorkflowStartV1WireFixture.WireMessage message) {
        return new WorkflowStartTransportMessage(
                WorkflowStartContract.DLQ_TOPIC,
                WorkflowStartContract.DLQ_ACCEPTOR_GROUP,
                message.keys(), message.tags(), message.body(), message.properties());
    }

    private void awaitRecoveryState(String expected) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            String state = jdbc.queryForObject(
                    "SELECT state FROM message_recovery", String.class);
            if (expected.equals(state)) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(jdbc.queryForObject(
                "SELECT state FROM message_recovery", String.class)).isEqualTo(expected);
    }

    private void awaitReplayAuditCount(int expected) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            int count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM audit_record
                    WHERE action_code LIKE 'WORKFLOW_RECOVERY_REPLAY_%'
                    """, Integer.class);
            if (count == expected) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_record
                WHERE action_code LIKE 'WORKFLOW_RECOVERY_REPLAY_%'
                """, Integer.class)).isEqualTo(expected);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void seedPublishedVersion(UUID tenantId,
                                      UUID definitionId,
                                      UUID versionId,
                                      UUID levelOneApprover,
                                      UUID levelTwoApprover,
                                      String conditionValue) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID draftId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workflow_definition
                  (tenant_id, definition_id, definition_code, definition_name, status,
                   created_by_type, updated_by_type)
                VALUES (?, ?, 'HIGH_RISK_APPROVAL', 'High risk approval', 'ACTIVE',
                        'SERVICE', 'SERVICE')
                """, uuid(tenantId), uuid(definitionId));
        jdbc.update("""
                INSERT INTO workflow_draft
                  (tenant_id, draft_id, definition_id, status, condition_field_code,
                   condition_operator, condition_value_type, condition_value_text,
                   approval_level_count, level1_approver_user_id, level2_approver_user_id,
                   validation_state, validation_digest, validated_at, consumed_at, created_at,
                   created_by_type, updated_by_type)
                VALUES (?, ?, ?, 'CONSUMED', 'SERVICE_RISK_LEVEL', 'EQUALS', 'STRING', ?,
                        2, ?, ?, 'VALID', ?, ?, ?, ?, 'SERVICE', 'SERVICE')
                """, uuid(tenantId), uuid(draftId), uuid(definitionId), conditionValue,
                uuid(levelOneApprover), uuid(levelTwoApprover), bytes(32, (byte) 0x31),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now.minusSeconds(1)));
        jdbc.update("""
                INSERT INTO workflow_version
                  (tenant_id, workflow_version_id, definition_id, version_no, source_draft_id,
                   status, condition_field_code, condition_operator, condition_value_type,
                   condition_value_text, approval_level_count, level1_approver_user_id,
                   level2_approver_user_id, snapshot_sha256, published_at, published_by_type)
                VALUES (?, ?, ?, 3, ?, 'PUBLISHED', 'SERVICE_RISK_LEVEL', 'EQUALS', 'STRING', ?,
                        2, ?, ?, ?, ?, 'SERVICE')
                """, uuid(tenantId), uuid(versionId), uuid(definitionId), uuid(draftId),
                conditionValue, uuid(levelOneApprover), uuid(levelTwoApprover),
                bytes(32, (byte) 0x32), Timestamp.from(now));
    }

    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static UUID binaryUuid(byte[] value) {
        ByteBuffer bytes = ByteBuffer.wrap(value);
        return new UUID(bytes.getLong(), bytes.getLong());
    }

    private static byte[] bytes(int size, byte value) {
        byte[] result = new byte[size];
        java.util.Arrays.fill(result, value);
        return result;
    }

    private static String readResource(String name) {
        try (var input = WorkflowStartStrictConsumerTest.class
                .getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("Missing test resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EligibilityStubConfiguration {

        @Bean
        @Primary
        TrackingEligibilityChecker trackingEligibilityChecker() {
            return new TrackingEligibilityChecker();
        }
    }

    static final class TrackingEligibilityChecker
            implements WorkflowApproverEligibilityChecker {

        int calls;
        boolean eligible = true;
        boolean unavailable;
        boolean calledInsideTransaction;
        TenantContextSource contextSource;
        UUID contextTenant;
        Runnable afterCheck = () -> { };

        @Override
        public boolean isEligibleApprover(UUID tenantId, UUID approverUserId) {
            calls++;
            calledInsideTransaction = TransactionSynchronizationManager
                    .isActualTransactionActive();
            contextSource = TenantContextHolder.current().source();
            contextTenant = TenantContextHolder.current().tenantId();
            if (unavailable) {
                throw new WorkflowIamUnavailableException();
            }
            afterCheck.run();
            return eligible;
        }

        void reset() {
            calls = 0;
            eligible = true;
            unavailable = false;
            calledInsideTransaction = false;
            contextSource = null;
            contextTenant = null;
            afterCheck = () -> { };
        }
    }
}
