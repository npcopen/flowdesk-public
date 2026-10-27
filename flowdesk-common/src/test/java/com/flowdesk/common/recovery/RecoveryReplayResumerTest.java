package com.flowdesk.common.recovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用真实 MyBatis 与独立数据库验证持久化人工重放租约，覆盖重启、并发领取、再次中断和过期工作者边界。
 */
class RecoveryReplayResumerTest {
    /**
     * 恢复扫描测试使用的服务级 Mapper 接口，继承公共 SQL 契约以覆盖与业务模块相同的 MyBatis 映射路径。
     */
    public interface ServiceRecoveryReplayMapper extends RecoveryReplayMapper { }
    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
    private JdbcTemplate jdbc;
    private JdbcTransactionManager transactions;
    private RecoveryReplayMapper mapper;

    /**
     * 为恢复租约测试创建独立 H2 数据库及真实 MyBatis Mapper，关闭缓存并初始化最小恢复表结构。
     */
    @BeforeEach
    void database() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:replay-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new JdbcTransactionManager(dataSource);
        var factory = new org.mybatis.spring.SqlSessionFactoryBean();
        var config = new org.apache.ibatis.session.Configuration();
        config.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        config.setCacheEnabled(false);
        config.getTypeHandlerRegistry().register(UUID.class,
                new com.flowdesk.common.mybatis.UuidBinaryTypeHandler());
        config.addMapper(ServiceRecoveryReplayMapper.class);
        factory.setConfiguration(config);
        factory.setDataSource(dataSource);
        try {
            mapper = new org.mybatis.spring.SqlSessionTemplate(factory.getObject())
                    .getMapper(ServiceRecoveryReplayMapper.class);
        } catch (Exception failure) { throw new IllegalStateException(failure); }

        jdbc.execute("""
                CREATE TABLE message_recovery (
                  tenant_id BINARY(16), recovery_id BINARY(16), source_record_id BINARY(16),
                  direction VARCHAR(16), state VARCHAR(16), next_attempt_at TIMESTAMP(6),
                  last_replayed_by_type VARCHAR(16), last_replayed_by_id BINARY(16),
                  last_replay_reason VARCHAR(255), last_replay_correlation_id BINARY(16),
                  lock_version BIGINT, updated_at TIMESTAMP(6), updated_by_type VARCHAR(16),
                  updated_by_id BINARY(16), PRIMARY KEY (tenant_id,recovery_id))
                """);
        jdbc.execute("""
                CREATE TABLE inbox_message (tenant_id BINARY(16),inbox_id BINARY(16),
                  correlation_id BINARY(16),PRIMARY KEY (tenant_id,inbox_id))
                """);
    }

    /**
     * 验证进程重启后仅在租约到期接管丢失任务，并保留原人工尝试、操作者及原因。
     */
    @Test
    void aNewProcessResumesLostWorkOnlyAfterItsLeaseAndPreservesTheHumanAttempt() {
        var original = seed("INBOX", "REPLAYING", NOW.plusSeconds(120));
        List<RecoveryReplayResumer.Task> resumed = new ArrayList<>();
        var restarted = resumer(task -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(lease(task.recoveryId())).isEqualTo(task.leaseUntil());
            resumed.add(task);
        });

        assertThat(restarted.resumeDue(NOW.plusSeconds(119))).isZero();
        assertThat(restarted.resumeDue(NOW.plusSeconds(120))).isEqualTo(1);
        assertThat(resumed).containsExactly(new RecoveryReplayResumer.Task(
                original.tenantId(), original.recoveryId(), original.sourceRecordId(),
                original.actorId(), original.reason(), original.attemptCorrelationId(),
                original.sourceCorrelationId(), NOW.plusSeconds(240)));
        assertThat(restarted.resumeDue(NOW.plusSeconds(121))).isZero();
        assertThat(jdbc.queryForObject("SELECT lock_version FROM message_recovery", Long.class))
                .isEqualTo(8L);
    }

    /**
     * 验证并发扫描实例只允许一个执行者领取同一租约，避免重复提交恢复任务。
     */
    @Test
    void concurrentInstancesClaimEachLeaseOnce() throws Exception {
        seed("INBOX", "REPLAYING", null);
        List<RecoveryReplayResumer.Task> resumed = new CopyOnWriteArrayList<>();
        var first = resumer(resumed::add);
        var second = resumer(resumed::add);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var a = workers.submit(() -> { start.await(); return first.resumeDue(NOW); });
            var b = workers.submit(() -> { start.await(); return second.resumeDue(NOW); });
            start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS) + b.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
        assertThat(resumed).hasSize(1);
    }

    /**
     * 验证再次崩溃或执行队列拒绝后仍可按新租约接管，人工尝试标识保持不变。
     */
    @Test
    void aSecondCrashOrFailedSubmissionRemainsRecoverableWithANewLease() {
        var original = seed("INBOX", "REPLAYING", null);
        var unavailable = resumer(task -> { throw new IllegalStateException("executor stopped"); });
        assertThat(unavailable.resumeDue(NOW)).isEqualTo(1);
        assertThat(lease(original.recoveryId())).isEqualTo(NOW.plusSeconds(120));
        List<RecoveryReplayResumer.Task> resumed = new ArrayList<>();
        var restarted = resumer(resumed::add);
        assertThat(restarted.resumeDue(NOW.plusSeconds(119))).isZero();
        assertThat(restarted.resumeDue(NOW.plusSeconds(120))).isEqualTo(1);
        assertThat(resumed.get(0).attemptCorrelationId()).isEqualTo(original.attemptCorrelationId());
        assertThat(resumed.get(0).leaseUntil()).isEqualTo(NOW.plusSeconds(240));
    }

    /**
     * 验证扫描器跳过尚未接纳、Outbox 和已解决记录，仅恢复各租户符合条件的 Inbox 任务。
     */
    @Test
    void scannerSkipsUnacceptedOutboxAndResolvedWorkAcrossTenants() {
        var acceptedA = seed("INBOX", "REPLAYING", null);
        var acceptedB = seed("INBOX", "REPLAYING", NOW);
        seed("INBOX", "OPEN", null);
        seed("INBOX", "RESOLVED", null);
        seed("OUTBOX", "REPLAYING", null);
        seed("INBOX", "REPLAYING", NOW.plusSeconds(1));
        List<RecoveryReplayResumer.Task> resumed = new ArrayList<>();
        assertThat(resumer(resumed::add).resumeDue(NOW)).isEqualTo(2);
        assertThat(resumed).extracting(RecoveryReplayResumer.Task::tenantId)
                .containsExactlyInAnyOrder(acceptedA.tenantId(), acceptedB.tenantId());
    }

    /**
     * 验证原 Inbox 关联标识缺失时保留 null，而人工尝试信息不完整的记录不会被执行。
     */
    @Test
    void missingInboxCorrelationRemainsNullableAndIncompleteHumanAttemptsAreSkipped() {
        var missingSource = seed("INBOX", "REPLAYING", null);
        jdbc.update("DELETE FROM inbox_message WHERE tenant_id=?", bytes(missingSource.tenantId()));
        var missingActor = seed("INBOX", "REPLAYING", null);
        jdbc.update("UPDATE message_recovery SET last_replayed_by_id=NULL WHERE tenant_id=?",
                bytes(missingActor.tenantId()));
        var missingAttempt = seed("INBOX", "REPLAYING", null);
        jdbc.update("UPDATE message_recovery SET last_replay_correlation_id=NULL WHERE tenant_id=?",
                bytes(missingAttempt.tenantId()));
        List<RecoveryReplayResumer.Task> resumed = new ArrayList<>();
        assertThat(resumer(resumed::add).resumeDue(NOW)).isEqualTo(1);
        assertThat(resumed.get(0).tenantId()).isEqualTo(missingSource.tenantId());
        assertThat(resumed.get(0).sourceCorrelationId()).isNull();
    }

    /**
     * 验证每轮扫描遵守批次上限，候选快照过期后不能领取已变更的租约。
     */
    @Test
    void eachScanIsBoundedAndStaleCandidateCannotClaimAChangedLease() {
        for (int i = 0; i < 34; i++) seed("INBOX", "REPLAYING", null);
        var candidates = mapper.findDue(NOW);
        assertThat(candidates).hasSize(32);
        var stale = candidates.get(0);
        jdbc.update("UPDATE message_recovery SET lock_version=lock_version+1 WHERE tenant_id=?",
                bytes(stale.tenantId()));
        assertThat(mapper.claim(stale, NOW.plusSeconds(120), NOW)).isZero();
        List<RecoveryReplayResumer.Task> resumed = new ArrayList<>();
        assertThat(resumer(resumed::add).resumeDue(NOW)).isEqualTo(32);
        assertThat(resumer(resumed::add).resumeDue(NOW)).isEqualTo(2);
        assertThat(resumed).hasSize(34);
    }

    /**
     * 使用真实恢复 Mapper、事务管理器及给定任务提交回调组装扫描器，便于观察提交边界。
     */
    private RecoveryReplayResumer resumer(java.util.function.Consumer<RecoveryReplayResumer.Task> handler) {
        return new RecoveryReplayResumer(mapper, transactions, handler, false);
    }

    /**
     * 插入指定方向、状态和租约的恢复事实及关联 Inbox，返回原人工尝试快照供接管断言。
     */
    private RecoveryReplayResumer.Task seed(String direction, String state, Instant lease) {
        var task = new RecoveryReplayResumer.Task(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "operator retry", UUID.randomUUID(),
                UUID.randomUUID(), lease);
        jdbc.update("""
                INSERT INTO message_recovery (tenant_id,recovery_id,source_record_id,direction,state,
                  next_attempt_at,last_replayed_by_type,last_replayed_by_id,last_replay_reason,
                  last_replay_correlation_id,lock_version,updated_at,updated_by_type)
                VALUES (?,?,?,?,?,?,'USER',?,?,?,7,?,'USER')
                """, bytes(task.tenantId()), bytes(task.recoveryId()), bytes(task.sourceRecordId()),
                direction, state, lease == null ? null : Timestamp.from(lease), bytes(task.actorId()),
                task.reason(), bytes(task.attemptCorrelationId()), Timestamp.from(NOW));
        jdbc.update("INSERT INTO inbox_message VALUES (?,?,?)", bytes(task.tenantId()),
                bytes(task.sourceRecordId()), bytes(task.sourceCorrelationId()));
        return task;
    }

    /**
     * 读取指定恢复记录当前租约截止时间，验证领取及再次接管的数据库结果。
     */
    private Instant lease(UUID recoveryId) {
        return jdbc.queryForObject("SELECT next_attempt_at FROM message_recovery WHERE recovery_id=?",
                Timestamp.class, bytes(recoveryId)).toInstant();
    }

    /**
     * 将 UUID 编码为固定 16 字节，保持测试 SQL 与生产 BINARY(16) 标识存储一致。
     */
    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
