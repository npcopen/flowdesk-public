package com.flowdesk.workflow.start;

import com.flowdesk.workflow.service.start.*;
import com.flowdesk.workflow.service.start.impl.*;
import com.flowdesk.workflow.service.recovery.*;
import com.flowdesk.workflow.service.recovery.impl.*;
import com.flowdesk.workflow.model.recovery.WorkflowRecoveryModels;
import com.flowdesk.workflow.dto.*;
import com.flowdesk.common.notification.NotificationWireCodec;
import com.flowdesk.common.recovery.RecoveryReplayResumer;
import com.flowdesk.workflow.service.WorkflowIdempotencyCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 恢复工作者测试，核验已提交任务续跑、旧租约失败隔离及来源终态后的无重复消费。
 */
class WorkflowRecoveryReplayWorkerTest {
    private final UUID tenant = UUID.randomUUID();
    private final UUID recovery = UUID.randomUUID();
    private final UUID inbox = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final UUID attempt = UUID.randomUUID();
    private final UUID correlation = UUID.randomUUID();
    private final Instant at = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
    private final byte[] body = ("{\"ticketId\":\"" + UUID.randomUUID() + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    private final List<Runnable> queued = new ArrayList<>();
    private JdbcTemplate jdbc;
    private SingleConnectionDataSource dataSource;
    private TransactionTemplate transactions;
    private WorkflowRecoveryMapper recoveryMapper;
    private WorkflowStartConsumer consumer;
    private RecoveryReplayResumer resumer;

    /**
     * 建立隔离测试数据源及当前用例协作对象，为租约与事务场景准备前置环境。
     */
    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:h2:mem:workflow-worker-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        new ResourceDatabasePopulator(new ClassPathResource("schema-test.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        var manager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(manager);
        recoveryMapper = com.flowdesk.workflow.support.WorkflowTestMappers.mapper(jdbc.getDataSource(), WorkflowRecoveryMapper.class);
        consumer = mock(WorkflowStartConsumer.class);
        var settlement = new WorkflowRecoveryExecutionServiceImpl(recoveryMapper, manager);
        var worker = new WorkflowRecoveryReplayWorker(queued::add, consumer, settlement);
        resumer = new RecoveryReplayResumer(com.flowdesk.workflow.support.WorkflowTestMappers.mapper(jdbc.getDataSource(), WorkflowRecoveryReplayMapper.class), manager, worker::resume, false);
        seedCommittedAttempt();
    }

    /**
     * 释放当前测试数据源与协作资源，避免连接或线程影响后续场景。
     */
    @AfterEach
    void close() { dataSource.destroy(); }

    /**
     * 验证提交后尚未调度的重放能续跑一次且保留最初人类历史，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void committedAttemptLostBeforeSubmissionIsResumedOnceWithOriginalHumanHistory() {
        when(consumer.consume(any())).thenAnswer(invocation -> {
            assertThat(invocation.<WorkflowStartTransportMessage>getArgument(0).body()).containsExactly(body);
            completeSource();
            return WorkflowStartConsumeResult.ack("WORKFLOW_START_PROCESSED", null);
        });
        assertThat(resumer.resumeDue(at)).isEqualTo(1);
        assertThat(resumer.resumeDue(at.plusSeconds(1))).isZero();
        assertThat(queued).hasSize(1);
        queued.get(0).run();
        queued.get(0).run();
        verify(consumer, times(1)).consume(any());
        var fact = recoveryMapper.findRecovery(bytes(tenant), bytes(recovery), false).stream().findFirst().orElseThrow();
        assertThat(fact.state()).isEqualTo("RESOLVED");
        assertThat(fact.replayCount()).isEqualTo(1);
        assertThat(fact.lastReplayedById()).isEqualTo(actor);
        assertThat(fact.lastReplayCorrelationId()).isEqualTo(attempt);
        assertThat(fact.lastReplayReason()).isEqualTo("Continue original human attempt");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_record", Integer.class)).isEqualTo(1);
        assertThat(resumer.resumeDue(at.plusSeconds(600))).isZero();
    }

    /**
     * 验证过期工作者失败不会重新开放新持有者租约，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void failureFromAnExpiredWorkerCannotReopenTheNewOwnersLease() {
        when(consumer.consume(any())).thenAnswer(invocation -> {
            assertThat(resumer.resumeDue(at.plus(RecoveryReplayResumer.LEASE_DURATION).plusSeconds(1)))
                    .isEqualTo(1);
            return WorkflowStartConsumeResult.retry("OLD_WORKER_FAILED");
        });
        assertThat(resumer.resumeDue(at)).isEqualTo(1);
        queued.get(0).run();
        assertThat(queued).hasSize(2);
        var leased = recoveryMapper.findRecovery(bytes(tenant), bytes(recovery), false).stream().findFirst().orElseThrow();
        assertThat(leased.state()).isEqualTo("REPLAYING");
        assertThat(leased.nextAttemptAt()).isEqualTo(RecoveryReplayResumer.leaseUntil(
                at.plus(RecoveryReplayResumer.LEASE_DURATION).plusSeconds(1)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_record", Integer.class)).isZero();
        doAnswer(invocation -> {
            completeSource();
            return WorkflowStartConsumeResult.ack("WORKFLOW_START_PROCESSED", null);
        }).when(consumer).consume(any());
        queued.get(1).run();
        assertThat(recoveryMapper.findRecovery(bytes(tenant), bytes(recovery), false).stream().findFirst().orElseThrow().state()).isEqualTo("RESOLVED");
        verify(consumer, times(2)).consume(any());
    }

    /**
     * 验证终态 Inbox 结清滞留重放且不再次消费，并检查相关业务事实或错误响应符合约定。
     */
    @ParameterizedTest
    @ValueSource(strings = {"PROCESSED", "REJECTED"})
    void terminalInboxClosesStrandedReplayWithoutConsumingAgain(String sourceStatus) {
        jdbc.update("""
                UPDATE inbox_message SET status=?,result_type=?,result_id=?,processed_at=?
                WHERE tenant_id=? AND inbox_id=?
                """, sourceStatus, "PROCESSED".equals(sourceStatus) ? "WORKFLOW_INSTANCE" : null,
                "PROCESSED".equals(sourceStatus) ? bytes(UUID.randomUUID()) : null,
                Timestamp.from(at.minusSeconds(5)), bytes(tenant), bytes(inbox));

        assertThat(resumer.resumeDue(at)).isEqualTo(1);
        queued.get(0).run();
        queued.get(0).run();

        verifyNoInteractions(consumer);
        var fact = recoveryMapper.findRecovery(bytes(tenant), bytes(recovery), false).stream().findFirst().orElseThrow();
        assertThat(fact.state()).isEqualTo("RESOLVED");
        assertThat(fact.nextAttemptAt()).isNull();
        assertThat(fact.replayCount()).isEqualTo(1);
        assertThat(fact.lastReplayedById()).isEqualTo(actor);
        assertThat(fact.lastReplayCorrelationId()).isEqualTo(attempt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_record", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT outcome FROM audit_record", String.class))
                .isEqualTo("PROCESSED".equals(sourceStatus) ? "SUCCEEDED" : "REJECTED");
        assertThat(resumer.resumeDue(at.plusSeconds(600))).isZero();
    }

    /**
     * 建立已提交但未执行完的 Inbox 重放事实，供后台续跑行为验证。
     */
    private void seedCommittedAttempt() {
        jdbc.update("""
                INSERT INTO inbox_message
                  (tenant_id,inbox_id,producer_service,message_id,business_intent_key,contract_ref,
                   contract_version,body_bytes,body_sha256,correlation_id,occurred_at,status,
                   attempt_count,last_error_code,first_received_at,last_attempt_at)
                VALUES (?,?,'flowdesk-ticket-service',?,?,'flowdesk.workflow.start.v1',
                        1,?,?,?,?,'RECOVERY_REQUIRED',6,'DEPENDENCY_UNAVAILABLE',?,?)
                """, bytes(tenant), bytes(inbox), bytes(UUID.randomUUID()), new byte[32], body,
                NotificationWireCodec.sha256(body), bytes(correlation), Timestamp.from(at.minusSeconds(60)),
                Timestamp.from(at.minusSeconds(60)), Timestamp.from(at.minusSeconds(30)));
        jdbc.update("""
                INSERT INTO message_recovery
                  (tenant_id,recovery_id,direction,source_record_id,business_intent_key,state,
                   attempt_count,last_error_code,recovery_required_at,replay_count,last_replayed_at,
                   last_replayed_by_type,last_replayed_by_id,last_replay_reason,last_replay_correlation_id,
                   updated_by_type,updated_by_id)
                VALUES (?,?,'INBOX',?,?,'REPLAYING',6,'DEPENDENCY_UNAVAILABLE',?,1,?,'USER',?,?,?,'USER',?)
                """, bytes(tenant), bytes(recovery), bytes(inbox), new byte[32], Timestamp.from(at.minusSeconds(20)),
                Timestamp.from(at.minusSeconds(10)), bytes(actor), "Continue original human attempt", bytes(attempt), bytes(actor));
    }

    /**
     * 事务内将原始来源置为终态，供滞留重放不再次消费的场景验证。
     */
    private void completeSource() {
        transactions.executeWithoutResult(tx -> {
            jdbc.update("""
                    UPDATE inbox_message SET status='PROCESSED',result_type='WORKFLOW_INSTANCE',result_id=?,processed_at=?
                    WHERE tenant_id=? AND inbox_id=?
                    """, bytes(UUID.randomUUID()), Timestamp.from(Instant.now()), bytes(tenant), bytes(inbox));
            jdbc.update("""
                    UPDATE message_recovery SET state='RESOLVED',resolved_at=?,next_attempt_at=NULL,
                        lock_version=lock_version+1 WHERE tenant_id=? AND recovery_id=?
                    """, Timestamp.from(Instant.now()), bytes(tenant), bytes(recovery));
        });
    }

    /**
     * 将 UUID 编码为数据库使用的二进制值，空标识保持为空。
     */
    private static byte[] bytes(UUID value) { return WorkflowIdempotencyCodec.uuidBytes(value); }
}
