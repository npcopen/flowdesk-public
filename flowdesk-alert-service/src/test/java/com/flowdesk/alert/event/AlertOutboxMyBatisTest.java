package com.flowdesk.alert.event;
import com.flowdesk.alert.service.impl.AlertOutboxDeliveryServiceImpl;
import com.flowdesk.alert.service.AlertOutboxDeliveryService;

import com.flowdesk.alert.mapper.AlertOutboxMapper;
import com.flowdesk.common.mybatis.UuidBinaryTypeHandler;
import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证告警发件箱 MyBatis 状态协调的行为约束；结合可控测试数据检查正常流程、失败边界或状态收敛，避免回归破坏既有契约。
 */
class AlertOutboxMyBatisTest {
    private final UUID tenant = UUID.randomUUID();
    private final Instant at = Instant.parse("2026-10-26T12:00:00Z");
    private JdbcTemplate jdbc;
    private AlertOutboxDeliveryService repository;

    /**
     * 初始化测试数据库、Mapper 与业务依赖，为每个发件箱并发场景建立隔离环境。
     */
    @BeforeEach
    void setup() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:outbox-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema-test.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        configuration.getTypeHandlerRegistry().register(UUID.class, new UuidBinaryTypeHandler());
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/alert/AlertOutboxMapper.xml"));
        repository = new AlertOutboxDeliveryServiceImpl(new SqlSessionTemplate(factory.getObject()).getMapper(AlertOutboxMapper.class), new DataSourceTransactionManager(dataSource));
    }

    /**
     * 验证过期发件箱持有者不能结算，接管后严格使用新的尝试次数、token 与版本。
     */
    @Test
    void expiredOwnershipCannotSettleAndTakeoverKeepsExactAttemptVersion() {
        UUID id = seed(0, "PENDING", null, null);
        var initial = repository.candidates(at, at.minusSeconds(30)).get(0);
        var first = inTenant(() -> repository.claim(initial, "first", at, at.minusSeconds(30)).orElseThrow());
        assertThat(first.outboxId()).isEqualTo(id);
        assertThat(first.attemptCount()).isEqualTo(1);
        assertThat(first.lockVersion()).isEqualTo(1);
        assertThat(first.body()).containsExactly(new byte[]{1, 2, 3});
        assertThat(inTenant(() -> repository.claim(initial, "stale", at, at.minusSeconds(30)))).isEmpty();
        // Equality is already expired: settlement requires locked_at strictly after cutoff.
        assertThat(inTenant(() -> repository.published(first, at.plusSeconds(30), at))).isFalse();
        var expired = repository.candidates(at.plusSeconds(31), at.plusSeconds(1)).get(0);
        var second = inTenant(() -> repository.claim(expired, "second", at.plusSeconds(31), at.plusSeconds(1)).orElseThrow());
        assertThat(second.attemptCount()).isEqualTo(2);
        assertThat(second.lockVersion()).isEqualTo(2);
        assertThat(inTenant(() -> repository.pending(first, at.plusSeconds(31), at.plusSeconds(1), at.plusSeconds(35), "UNKNOWN"))).isFalse();
        assertThat(inTenant(() -> repository.published(second, at.plusSeconds(31), at.plusSeconds(1)))).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event", String.class)).isEqualTo("PUBLISHED");
    }

    /**
     * 验证第八次投递耗尽后只能转入人工恢复，不能因租约接管额外获得一次发送机会。
     */
    @Test
    void eighthAttemptIsSettledIntoRecoveryWithoutGrantingAnotherSend() {
        seed(8, "PUBLISHING", "crashed", at.minusSeconds(31));
        var candidate = repository.candidates(at, at.minusSeconds(30)).get(0);
        assertThat(inTenant(() -> repository.claim(candidate, "send-again", at, at.minusSeconds(30)))).isEmpty();
        var settlement = inTenant(() -> repository.claimRecovery(candidate, "settle", at, at.minusSeconds(30)).orElseThrow());
        assertThat(settlement.attemptCount()).isEqualTo(8);
        assertThat(inTenant(() -> repository.recover(settlement, at, at.minusSeconds(30), "UNKNOWN"))).isTrue();
        assertThat(inTenant(() -> repository.recover(settlement, at, at.minusSeconds(30), "UNKNOWN"))).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event", String.class)).isEqualTo("RECOVERY_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT state FROM message_recovery", String.class)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM message_recovery", Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_record", Integer.class)).isEqualTo(1);
    }

    /**
     * 验证跨租户后台候选扫描遵守批次上限，避免单次扫描无界扩大。
     */
    @Test
    void crossTenantCandidateScanRemainsBounded() {
        for (int i = 0; i < 55; i++) seed(0, "PENDING", null, null);
        assertThat(repository.candidates(at, at.minusSeconds(30))).hasSize(50);
    }

    /**
     * 创建本测试所需的租户、集成或消息业务事实，并返回后续请求与数据库断言使用的资源标识。
     */
    private UUID seed(int attempts, String status, String token, Instant lockedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_event(tenant_id,outbox_id,message_id,publication_key,business_intent_key,
                  aggregate_type,aggregate_id,contract_ref,contract_version,body_bytes,body_sha256,
                  correlation_id,status,attempt_count,next_attempt_at,locked_by,locked_at,lock_version,
                  created_at,created_by_type,updated_at,updated_by_type)
                VALUES(?,?,?,?,?,'INCIDENT',?,?,1,?,?,?, ?,?,?,?, ?,0,?,'SERVICE',?,'SERVICE')
                """, bytes(tenant), bytes(id), bytes(UUID.randomUUID()), key(), key(), bytes(UUID.randomUUID()),
                AlertEventContract.INCIDENT_RECOVERED.contractRef(), new byte[]{1,2,3}, key(), bytes(UUID.randomUUID()),
                status, attempts, Timestamp.from(at), token, lockedAt == null ? null : Timestamp.from(lockedAt),
                Timestamp.from(at), Timestamp.from(at));
        return id;
    }
    /**
     * 在指定租户可信上下文中执行测试操作，退出作用域时恢复原线程上下文。
     */
    private <T> T inTenant(Supplier<T> action) {
        try (var scope = TenantContextScope.open(TenantContext.ofPersistedTask(tenant,
                ActorRef.service("flowdesk-alert-service"), UUID.randomUUID()))) {
            return action.get();
        }
    }
    /**
     * 把 UUID 编码为固定 16 字节，适配测试或数据库映射中的二进制标识。
     */
    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
    /**
     * 生成符合当前测试用途的固定密钥字节，使签名或摘要断言可重复。
     */
    private static byte[] key() {
        return ByteBuffer.allocate(32).put(bytes(UUID.randomUUID())).put(bytes(UUID.randomUUID())).array();
    }
}
