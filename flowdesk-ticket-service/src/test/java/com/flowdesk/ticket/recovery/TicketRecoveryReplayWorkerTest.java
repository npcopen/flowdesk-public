package com.flowdesk.ticket.recovery;

import com.flowdesk.ticket.mapper.*;
import com.flowdesk.ticket.model.*;
import com.flowdesk.ticket.persistence.Uuids;

import com.flowdesk.common.notification.NotificationWireCodec;
import com.flowdesk.common.recovery.RecoveryReplayResumer;
import com.flowdesk.ticket.persistence.Uuids;
import com.flowdesk.ticket.reverse.WorkflowReverseConsumeResult;
import com.flowdesk.ticket.reverse.WorkflowReverseConsumer;
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
 * 工单模块回归测试，覆盖该组件的正常流程、边界条件与关键一致性约束。
 */
class TicketRecoveryReplayWorkerTest {
    private final UUID tenant = UUID.randomUUID();
    private final UUID recovery = UUID.randomUUID();
    private final UUID inbox = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final UUID attempt = UUID.randomUUID();
    private final UUID correlation = UUID.randomUUID();
    private final Instant at = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
    private final byte[] body = ("{\"workflowInstanceId\":\"" + UUID.randomUUID() + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    private final List<Runnable> queued = new ArrayList<>();
    private JdbcTemplate jdbc;
    private SingleConnectionDataSource dataSource;
    private TransactionTemplate transactions;
    private TicketRecoveryPersistenceMapper mapper;
    private WorkflowReverseConsumer consumer;
    private RecoveryReplayResumer resumer;

    /**
     * 清理上次场景的持久化与替身状态，再建立本场景所需基础数据。
     */
    @BeforeEach
    void setUp() throws Exception {
        dataSource = new SingleConnectionDataSource("jdbc:h2:mem:ticket-worker-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        new ResourceDatabasePopulator(new ClassPathResource("schema-test.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        var manager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(manager);
        var factory = new org.mybatis.spring.SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setCacheEnabled(false);
        configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        configuration.getTypeHandlerRegistry().register(UUID.class,
                new com.flowdesk.common.mybatis.UuidBinaryTypeHandler());
        configuration.addMapper(TicketRecoveryReplayMapper.class);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/recovery/*.xml"));
        var session = new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        mapper = session.getMapper(TicketRecoveryPersistenceMapper.class);
        consumer = mock(WorkflowReverseConsumer.class);
        var worker = new com.flowdesk.ticket.recovery.impl.TicketRecoveryReplayWorker(queued::add, mapper, consumer, manager);
        resumer = new RecoveryReplayResumer(session.getMapper(TicketRecoveryReplayMapper.class), manager, worker::resume, false);
        seedCommittedAttempt();
    }

    /**
     * 关闭组件持有的资源并终止后续处理。
     */
    @AfterEach
    void close() { dataSource.destroy(); }

    /**
     * 验证受理提交后尚未调度的任务能恢复一次，并保留原用户历史。
     */
    @Test
    void committedAttemptLostBeforeSubmissionIsResumedOnceWithOriginalHumanHistory() {
        when(consumer.consume(any())).thenAnswer(invocation -> {
            assertThat(invocation.<com.flowdesk.ticket.reverse.WorkflowReverseTransportMessage>getArgument(0).body())
                    .containsExactly(body);
            completeSource();
            return WorkflowReverseConsumeResult.ack("WORKFLOW_REVERSE_PROCESSED");
        });
        assertThat(resumer.resumeDue(at)).isEqualTo(1);
        assertThat(resumer.resumeDue(at.plusSeconds(1))).isZero();
        assertThat(queued).hasSize(1);
        queued.get(0).run();
        queued.get(0).run();
        verify(consumer, times(1)).consume(any());
        var fact = mapper.findSelect(Uuids.toBytes(tenant), Uuids.toBytes(recovery), false).stream().findFirst().orElseThrow();
        assertThat(fact.state()).isEqualTo("RESOLVED");
        assertThat(fact.replayCount()).isEqualTo(1);
        assertThat(fact.lastReplayedById()).isEqualTo(actor);
        assertThat(fact.lastReplayCorrelationId()).isEqualTo(attempt);
        assertThat(fact.lastReplayReason()).isEqualTo("Continue original human attempt");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_record", Integer.class)).isEqualTo(1);
        assertThat(resumer.resumeDue(at.plusSeconds(600))).isZero();
    }

    /**
     * 验证过期工作器失败不能重开新所有者持有的租约。
     */
    @Test
    void failureFromAnExpiredWorkerCannotReopenTheNewOwnersLease() {
        when(consumer.consume(any())).thenAnswer(invocation -> {
            assertThat(resumer.resumeDue(at.plus(RecoveryReplayResumer.LEASE_DURATION).plusSeconds(1)))
                    .isEqualTo(1);
            return WorkflowReverseConsumeResult.retry("OLD_WORKER_FAILED");
        });
        assertThat(resumer.resumeDue(at)).isEqualTo(1);
        queued.get(0).run();
        assertThat(queued).hasSize(2);
        var leased = mapper.findSelect(Uuids.toBytes(tenant), Uuids.toBytes(recovery), false).stream().findFirst().orElseThrow();
        assertThat(leased.state()).isEqualTo("REPLAYING");
        assertThat(leased.nextAttemptAt()).isEqualTo(RecoveryReplayResumer.leaseUntil(
                at.plus(RecoveryReplayResumer.LEASE_DURATION).plusSeconds(1)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_record", Integer.class)).isZero();
        doAnswer(invocation -> {
            completeSource();
            return WorkflowReverseConsumeResult.ack("WORKFLOW_REVERSE_PROCESSED");
        }).when(consumer).consume(any());
        queued.get(1).run();
        assertThat(mapper.findSelect(Uuids.toBytes(tenant), Uuids.toBytes(recovery), false).stream().findFirst().orElseThrow().state()).isEqualTo("RESOLVED");
        verify(consumer, times(2)).consume(any());
    }

    /**
     * 验证来源已终态时直接结束遗留重放，不再次消费。
     */
    @ParameterizedTest
    @ValueSource(strings = {"PROCESSED", "REJECTED"})
    void terminalInboxClosesStrandedReplayWithoutConsumingAgain(String sourceStatus) {
        jdbc.update("""
                UPDATE inbox_message SET status=?,result_type=?,result_id=?,processed_at=?
                WHERE tenant_id=? AND inbox_id=?
                """, sourceStatus, "PROCESSED".equals(sourceStatus) ? "TICKET" : null,
                "PROCESSED".equals(sourceStatus) ? bytes(UUID.randomUUID()) : null,
                Timestamp.from(at.minusSeconds(5)), bytes(tenant), bytes(inbox));

        assertThat(resumer.resumeDue(at)).isEqualTo(1);
        queued.get(0).run();
        queued.get(0).run();

        verifyNoInteractions(consumer);
        var fact = mapper.findSelect(Uuids.toBytes(tenant), Uuids.toBytes(recovery), false).stream().findFirst().orElseThrow();
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
     * 保存本场景前置业务事实，使用参数控制租户、身份、状态及时间边界。
     */
    private void seedCommittedAttempt() {
        jdbc.update("""
                INSERT INTO inbox_message
                  (tenant_id,inbox_id,producer_service,message_id,business_intent_key,contract_ref,
                   contract_version,body_bytes,body_sha256,correlation_id,occurred_at,status,
                   attempt_count,last_error_code,first_received_at,last_attempt_at)
                VALUES (?,?,'flowdesk-workflow-service',?,?,'flowdesk.workflow.approval-finalized.v1',
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
     * 将测试来源推进到终态，供验证遗留重放直接收敛而不重新消费。
     */
    private void completeSource() {
        transactions.executeWithoutResult(tx -> {
            jdbc.update("""
                    UPDATE inbox_message SET status='PROCESSED',result_type='TICKET',result_id=?,processed_at=?
                    WHERE tenant_id=? AND inbox_id=?
                    """, bytes(UUID.randomUUID()), Timestamp.from(Instant.now()), bytes(tenant), bytes(inbox));
            jdbc.update("""
                    UPDATE message_recovery SET state='RESOLVED',resolved_at=?,next_attempt_at=NULL,
                        lock_version=lock_version+1 WHERE tenant_id=? AND recovery_id=?
                    """, Timestamp.from(Instant.now()), bytes(tenant), bytes(recovery));
        });
    }

    /**
     * 转换业务标识与规范 UUID 字节表示，遵循既有空值与格式校验约定。
     */
    private static byte[] bytes(UUID value) { return Uuids.toBytes(value); }
}
