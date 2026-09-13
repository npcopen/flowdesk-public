package com.flowdesk.workflow.outbox;

import com.flowdesk.workflow.event.WorkflowReverseEventCodec;
import com.flowdesk.workflow.event.WorkflowReverseEventContract;
import com.flowdesk.workflow.service.WorkflowIdempotencyCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:workflow-outbox;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "flowdesk.workflow.outbox.publisher.enabled=false", "FLOWDESK_WORKFLOW_DB_PASSWORD="
})
class WorkflowOutboxPublisherIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TICKET = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID INSTANCE = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID DEFINITION = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000003");
    private static final UUID VERSION = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000004");
    private static final UUID CORRELATION = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000005");
    private static final UUID CAUSATION = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000006");
    private static final Instant OCCURRED = Instant.parse("2026-08-24T12:00:00.000000Z");
    private static final byte[] START_INTENT = WorkflowIdempotencyCodec.derivedKey(
            "FD-WORKFLOW-START-V1", TENANT, TICKET);

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    private WorkflowOutboxRepository repository;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM message_recovery");
        jdbc.update("DELETE FROM audit_record");
        jdbc.update("DELETE FROM outbox_event");
        repository = new WorkflowOutboxRepository(jdbc);
    }

    @Test
    void sendsBothFrozenEnvelopesWithoutReserializingBody() {
        byte[] establishedBody = WorkflowReverseEventCodec.instanceEstablished(
                TICKET, INSTANCE, START_INTENT, DEFINITION, VERSION, 2, "RUNNING", OCCURRED);
        UUID establishedId = insert(WorkflowReverseEventContract.InstanceEstablished.CONTRACT_REF,
                establishedBody, WorkflowReverseEventCodec.instanceEstablishedIntent(TENANT, TICKET));
        List<WorkflowOutboxMessage> sent = new ArrayList<>();
        MutableClock clock = new MutableClock(OCCURRED.plusSeconds(1));
        WorkflowOutboxPublisher publisher = new WorkflowOutboxPublisher(repository, message -> {
            sent.add(message); return WorkflowOutboxSendResult.sendOk();
        }, transactionManager, clock);

        assertThat(publisher.publishOnce().published()).isEqualTo(1);
        assertThat(sent).singleElement().satisfies(message -> {
            assertThat(message.topic()).isEqualTo(
                    WorkflowReverseEventContract.InstanceEstablished.TOPIC);
            assertThat(message.body()).containsExactly(establishedBody);
            assertThat(UUID.fromString(message.keys()).toString()).isEqualTo(message.keys());
            assertThat(message.userProperties().get("fd_message_id")).isEqualTo(message.keys());
            assertThat(message.userProperties()).containsEntry("fd_contract_ref",
                            WorkflowReverseEventContract.InstanceEstablished.CONTRACT_REF)
                    .containsEntry("fd_event_type",
                            WorkflowReverseEventContract.InstanceEstablished.EVENT_TYPE)
                    .containsEntry("fd_producer", WorkflowReverseEventContract.PRODUCER_SERVICE)
                    .containsEntry("fd_tenant_id", TENANT.toString())
                    .containsEntry("fd_aggregate_id", INSTANCE.toString())
                    .containsEntry("fd_correlation_id", CORRELATION.toString())
                    .containsEntry("fd_causation_id", CAUSATION.toString())
                    .containsEntry("fd_occurred_at", "2026-08-24T12:00:00.000000Z");
            assertThat(message.userProperties()).hasSize(14);
        });
        assertThat(status(establishedId)).isEqualTo("PUBLISHED");

        byte[] finalBody = WorkflowReverseEventCodec.approvalFinalized(
                TICKET, INSTANCE, START_INTENT, DEFINITION, VERSION, 2, "PASSED", OCCURRED);
        UUID finalId = insert(WorkflowReverseEventContract.ApprovalFinalized.CONTRACT_REF,
                finalBody, WorkflowReverseEventCodec.approvalFinalizedIntent(TENANT, INSTANCE));
        sent.clear();
        assertThat(publisher.publishOnce().published()).isEqualTo(1);
        assertThat(sent).singleElement().satisfies(message -> {
            assertThat(message.topic()).isEqualTo(
                    WorkflowReverseEventContract.ApprovalFinalized.TOPIC);
            assertThat(message.body()).containsExactly(finalBody);
            assertThat(message.userProperties().get("fd_event_type")).isEqualTo(
                    WorkflowReverseEventContract.ApprovalFinalized.EVENT_TYPE);
        });
        assertThat(status(finalId)).isEqualTo("PUBLISHED");
    }

    @Test
    void exactlyEightFailuresCreateOneWorkflowOutboxRecovery() {
        byte[] body = WorkflowReverseEventCodec.instanceEstablished(
                TICKET, INSTANCE, START_INTENT, DEFINITION, VERSION, 2, "RUNNING", OCCURRED);
        UUID outboxId = insert(WorkflowReverseEventContract.InstanceEstablished.CONTRACT_REF,
                body, WorkflowReverseEventCodec.instanceEstablishedIntent(TENANT, TICKET));
        MutableClock clock = new MutableClock(OCCURRED);
        List<WorkflowOutboxMessage> sent = new ArrayList<>();
        WorkflowOutboxPublisher publisher = new WorkflowOutboxPublisher(repository, message -> {
            sent.add(message);
            return WorkflowOutboxSendResult.notConfirmed("BROKER_UNAVAILABLE", " broker\r\n unavailable ");
        }, transactionManager, clock);
        for (var backoff : WorkflowReverseEventContract.FAILURE_BACKOFFS) {
            assertThat(publisher.publishOnce().rescheduled()).isEqualTo(1);
            clock.set(clock.instant().plus(backoff));
        }
        assertThat(publisher.publishOnce().recovered()).isEqualTo(1);
        assertThat(sent).hasSize(8).allSatisfy(message -> assertThat(message.body()).containsExactly(body));
        assertThat(status(outboxId)).isEqualTo("RECOVERY_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM outbox_event", Integer.class))
                .isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT direction FROM message_recovery", String.class))
                .isEqualTo("OUTBOX");
        assertThat(jdbc.queryForObject("SELECT state FROM message_recovery", String.class))
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT last_error_summary FROM message_recovery", String.class))
                .isEqualTo("broker unavailable");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_record", Integer.class))
                .isEqualTo(1);
        clock.set(clock.instant().plusSeconds(3600));
        assertThat(publisher.publishOnce().scanned()).isZero();
    }

    private UUID insert(String contract, byte[] body, byte[] intent) {
        UUID outboxId = UUID.randomUUID(); UUID messageId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_event
                  (tenant_id,outbox_id,message_id,publication_key,business_intent_key,
                   aggregate_type,aggregate_id,contract_ref,contract_version,body_bytes,
                   body_sha256,correlation_id,causation_id,status,attempt_count,next_attempt_at,
                   lock_version,created_at,created_by_type,updated_at,updated_by_type)
                VALUES (?,?,?,?,?,'WORKFLOW_INSTANCE',?,?,1,?,?,?,?, 'PENDING',0,?,0,?,
                        'SERVICE',?,'SERVICE')
                """, bytes(TENANT), bytes(outboxId), bytes(messageId),
                WorkflowIdempotencyCodec.publicationKey(TENANT, messageId), intent,
                bytes(INSTANCE), contract, body, sha256(body), bytes(CORRELATION), bytes(CAUSATION),
                Timestamp.from(OCCURRED), Timestamp.from(OCCURRED), Timestamp.from(OCCURRED));
        return outboxId;
    }
    private String status(UUID outboxId) {
        return jdbc.queryForObject("SELECT status FROM outbox_event WHERE tenant_id=? AND outbox_id=?",
                String.class, bytes(TENANT), bytes(outboxId));
    }
    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits()).array();
    }
    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static final class MutableClock extends Clock {
        private Instant value;
        private MutableClock(Instant value) { this.value = value; }
        void set(Instant value) { this.value = value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}
