package com.flowdesk.notification;
import com.flowdesk.notification.service.NotificationInboxService;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.recovery.RecoveryReplayResumer;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.notification.recovery.mapper.NotificationRecoverySourceMapper;
import com.flowdesk.notification.recovery.NotificationRecoveryReplayService;
import com.flowdesk.notification.event.NotificationConsumeResult;
import com.flowdesk.notification.event.NotificationConsumerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通知恢复集成测试，覆盖死信配对、原信封重放、重启续跑及旧租约隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationRecoveryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private NotificationConsumerService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired private RecoveryReplayResumer resumer;
    @Autowired private NotificationRecoveryReplayService recoveryService;
    @Autowired private NotificationInboxService inboxes;
    @Autowired private NotificationRecoverySourceMapper replaySources;
    @Autowired @Qualifier("notificationRecoverySqlSessionFactory")
    private org.apache.ibatis.session.SqlSessionFactory recoveryFactory;
    @Autowired private com.flowdesk.notification.recovery.mapper.NotificationRecoveryReplayMapper replayMapper;
    @Autowired @Qualifier("notificationRecoveryTransactionManager")
    private PlatformTransactionManager recoveryTransactions;

    @Autowired @Qualifier("notificationSqlSessionFactory")
    private org.apache.ibatis.session.SqlSessionFactory runtimeFactory;
    @Autowired @Qualifier("transactionManager")
    private PlatformTransactionManager runtimeTransactions;

    /**
     * 验证运行时与恢复映射使用分离账号及匹配事务管理器，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void runtimeAndRecoveryMappersUseDistinctAccountsAndMatchingTransactions() {
        var runtime = runtimeFactory.getConfiguration();
        var recovery = recoveryFactory.getConfiguration();
        assertThat(runtimeFactory).isNotSameAs(recoveryFactory);
        assertThat(runtime.hasMapper(com.flowdesk.notification.recovery.mapper.NotificationRecoverySourceMapper.class)).isFalse();
        assertThat(runtime.hasMapper(com.flowdesk.notification.recovery.mapper.NotificationRecoveryReplayMapper.class)).isFalse();
        assertThat(recovery.hasMapper(com.flowdesk.notification.recovery.mapper.NotificationRecoverySourceMapper.class)).isTrue();
        assertThat(recovery.hasMapper(com.flowdesk.notification.recovery.mapper.NotificationRecoveryReplayMapper.class)).isTrue();
        var runtimeDataSource = runtime.getEnvironment().getDataSource();
        var recoveryDataSource = recovery.getEnvironment().getDataSource();
        assertThat(runtimeDataSource).isNotSameAs(recoveryDataSource);
        assertThat(((org.springframework.jdbc.datasource.DataSourceTransactionManager) runtimeTransactions).getDataSource()).isSameAs(runtimeDataSource);
        assertThat(((org.springframework.jdbc.datasource.DataSourceTransactionManager) recoveryTransactions).getDataSource()).isSameAs(recoveryDataSource);
        var bound = runtime.getMappedStatement("com.flowdesk.notification.mapper.NotificationInboxMapper.findById")
                .getBoundSql(java.util.Map.of("tenantId", UUID.randomUUID(), "inboxId", UUID.randomUUID(), "lock", false));
        assertThat(bound.getSql()).contains("NULL AS body_bytes");
    }

    /**
     * 注册当前集成测试所需的隔离数据库、身份密钥和组件配置。
     */
    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        NotificationIntegrationFixtures.properties(registry, "notification-recovery");
        registry.add("flowdesk.recovery.resume-enabled", () -> "false");
    }

    /**
     * 清空本测试涉及的持久化表，隔离用例之间的 Inbox、恢复、审计及业务状态。
     */
    @BeforeEach
    void clean() {
        for (String table : List.of("message_recovery", "audit_record", "inbox_message", "notification")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    /**
     * 验证死信来源只产生一次重放副作用并同时结清 Inbox 和恢复记录，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void dlqSourceCanBeReplayedOnceAndSettlesBothInboxAndRecovery() throws Exception {
        NotificationIntegrationFixtures.Delivery delivery = NotificationIntegrationFixtures.delivery(
                NotificationEventContract.TICKET, NotificationIntegrationFixtures.USER_A,
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                "ASSIGNMENT_NOTICE", "恢复后送达");

        assertThat(service.acceptDlq(delivery.dlq()))
                .isEqualTo(NotificationConsumeResult.ack("NOTIFICATION_RECOVERY_REQUIRED"));
        UUID recoveryId = recoveryFor(delivery);

        mockMvc.perform(get("/api/v1/notification-message-recoveries/" + recoveryId)
                        .header("Authorization", "Bearer " + NotificationIntegrationFixtures.recoveryAdminToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"lv:0\""))
                .andExpect(jsonPath("$.data.direction").value("INBOX"))
                .andExpect(jsonPath("$.data.state").value("OPEN"))
                .andExpect(jsonPath("$.data.attemptCount").value(6))
                .andExpect(jsonPath("$.data.replayCount").value(0));

        mockMvc.perform(post("/api/v1/notification-message-recoveries/" + recoveryId + "/replays")
                        .header("Authorization", "Bearer " + NotificationIntegrationFixtures.recoveryAdminToken())
                        .header("If-Match", "\"lv:0\"")
                        .header("Idempotency-Key", "notification-replay-0001")
                        .contentType("application/json")
                        .content("{\"reason\":\"核验原始消息后人工重放\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("ETag", "\"lv:2\""))
                .andExpect(jsonPath("$.data.state").value("RESOLVED"))
                .andExpect(jsonPath("$.data.replayCount").value(1))
                .andExpect(jsonPath("$.data.lockVersion").value(2));

        assertThat(jdbc.queryForObject("SELECT status FROM inbox_message WHERE tenant_id=? AND message_id=?",
                String.class, NotificationIntegrationFixtures.bytes(NotificationIntegrationFixtures.TENANT_A),
                NotificationIntegrationFixtures.bytes(delivery.messageId()))).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class)).isEqualTo(1);

        // The original command is a read-only replay even with its original ETag.
        mockMvc.perform(post("/api/v1/notification-message-recoveries/" + recoveryId + "/replays")
                        .header("Authorization", "Bearer " + NotificationIntegrationFixtures.recoveryAdminToken())
                        .header("If-Match", "\"lv:0\"")
                        .header("Idempotency-Key", "notification-replay-0001")
                        .contentType("application/json")
                        .content("{\"reason\":\"核验原始消息后人工重放\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("ETag", "\"lv:2\""))
                .andExpect(jsonPath("$.data.replayCount").value(1));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_record WHERE action_code='NOTIFICATION_RECOVERY_REPLAY_CLAIMED'",
                Integer.class)).isEqualTo(1);
    }

    /**
     * 验证不完整的原始持久化事实被关闭且不会进入消费重放，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void incompletePersistedSourceIsClosedWithoutReplayingIt() throws Exception {
        NotificationIntegrationFixtures.Delivery delivery = NotificationIntegrationFixtures.delivery(
                NotificationEventContract.WORKFLOW, NotificationIntegrationFixtures.USER_A,
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                "ASSIGNMENT_NOTICE", "不可安全重放");

        assertThat(service.acceptDlq(delivery.dlq()))
                .isEqualTo(NotificationConsumeResult.ack("NOTIFICATION_RECOVERY_REQUIRED"));
        UUID recoveryId = recoveryFor(delivery);
        jdbc.update("UPDATE inbox_message SET body_bytes=NULL WHERE tenant_id=? AND message_id=?",
                NotificationIntegrationFixtures.bytes(NotificationIntegrationFixtures.TENANT_A),
                NotificationIntegrationFixtures.bytes(delivery.messageId()));

        mockMvc.perform(post("/api/v1/notification-message-recoveries/" + recoveryId + "/replays")
                        .header("Authorization", "Bearer " + NotificationIntegrationFixtures.recoveryAdminToken())
                        .header("If-Match", "\"lv:0\"")
                        .header("Idempotency-Key", "notification-replay-invalid-0001")
                        .contentType("application/json")
                        .content("{\"reason\":\"源记录不完整，停止重放\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RECOVERY_NOT_REPLAYABLE"));

        mockMvc.perform(get("/api/v1/notification-message-recoveries/" + recoveryId)
                        .header("Authorization", "Bearer " + NotificationIntegrationFixtures.recoveryAdminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("RESOLVED"))
                .andExpect(jsonPath("$.data.lastErrorCode").value("NON_REPLAYABLE"))
                .andExpect(jsonPath("$.data.replayCount").value(0));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_record WHERE action_code='NOTIFICATION_RECOVERY_NON_REPLAYABLE'",
                Integer.class)).isEqualTo(1);
    }

    /**
     * 验证普通 Inbox 读取隐藏重放字节而恢复读取保留不可变来源，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void runtimeInboxReadsHideReplayBytesAndRecoveryReadsPreserveImmutableSource() {
        var delivery = NotificationIntegrationFixtures.delivery(NotificationEventContract.TICKET,
                NotificationIntegrationFixtures.USER_A, UUID.randomUUID(), "ASSIGNMENT_NOTICE", "source access");
        assertThat(service.acceptDlq(delivery.dlq()).disposition())
                .isEqualTo(NotificationConsumeResult.Disposition.ACK);
        UUID inboxId = NotificationIntegrationFixtures.uuid(jdbc.queryForObject(
                "SELECT inbox_id FROM inbox_message", byte[].class));
        var context = TenantContext.ofValidatedBrokerMessage(NotificationIntegrationFixtures.TENANT_A,
                NotificationEventContract.TICKET.producerService(), UUID.randomUUID());
        try (var ignored = TenantContextScope.open(context)) {
            var metadata = inboxes.findInbox(context.tenantId(), inboxId, false).orElseThrow();
            assertThat(metadata.bodyBytes()).isNull();
            assertThat(metadata.bodySha256()).isNotEmpty();

            var source = replaySources.findSource(context.tenantId(), inboxId, false).stream().findFirst().orElseThrow();
            assertThat(source.bodyBytes()).isEqualTo(delivery.body());
            byte[] exposedCopy = source.bodyBytes();
            exposedCopy[0] ^= 1;
            assertThat(source.bodyBytes()).isEqualTo(delivery.body());
        }
    }

    /**
     * 按原始来源查找恢复标识，供后续人工重放及状态结算断言。
     */
    private UUID recoveryFor(NotificationIntegrationFixtures.Delivery delivery) {
        byte[] value = jdbc.queryForObject("""
                SELECT r.recovery_id FROM message_recovery r
                JOIN inbox_message i ON i.tenant_id=r.tenant_id AND i.inbox_id=r.source_record_id
                WHERE i.tenant_id=? AND i.message_id=?
                """, byte[].class, NotificationIntegrationFixtures.bytes(NotificationIntegrationFixtures.TENANT_A),
                NotificationIntegrationFixtures.bytes(delivery.messageId()));
        return NotificationIntegrationFixtures.uuid(value);
    }

    /**
     * 验证已提交过期重放能在重启后续跑且不创建重复通知，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void expiredCommittedReplayIsResumedAfterRestartWithoutDuplicatingNotification() {
        var delivery = NotificationIntegrationFixtures.delivery(NotificationEventContract.TICKET,
                NotificationIntegrationFixtures.USER_A, UUID.randomUUID(), "ASSIGNMENT_NOTICE", "restart");
        service.acceptDlq(delivery.dlq());
        UUID recovery = recoveryFor(delivery);
        Instant at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        persistCommittedClaim(recovery, RecoveryReplayResumer.leaseUntil(at), UUID.randomUUID(), at);
        assertThat(resumer.resumeDue(at)).isZero();
        assertThat(resumer.resumeDue(at.plusSeconds(121))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM message_recovery", String.class)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT replay_count FROM message_recovery", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class)).isEqualTo(1);
        assertThat(resumer.resumeDue(at.plusSeconds(600))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_record WHERE action_code='NOTIFICATION_PROCESSED'",
                Integer.class)).isEqualTo(1);
    }

    /**
     * 验证遗留领取及失去接管的任务可再次恢复且旧工作者被租约隔离，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void legacyClaimAndLostTakeoverCanBeResumedAgainAndStaleWorkerIsFenced() {
        var delivery = NotificationIntegrationFixtures.delivery(NotificationEventContract.WORKFLOW,
                NotificationIntegrationFixtures.USER_A, UUID.randomUUID(), "ASSIGNMENT_NOTICE", "takeover");
        service.acceptDlq(delivery.dlq());
        UUID recovery = recoveryFor(delivery);
        Instant at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        persistCommittedClaim(recovery, null, UUID.randomUUID(), at);
        AtomicReference<RecoveryReplayResumer.Task> lost = new AtomicReference<>();
        var interruptedProcess = new RecoveryReplayResumer(replayMapper, recoveryTransactions, lost::set, false);
        assertThat(interruptedProcess.resumeDue(at)).isEqualTo(1);
        assertThat(resumer.resumeDue(at.plusSeconds(1))).isZero();
        assertThat(resumer.resumeDue(at.plusSeconds(121))).isEqualTo(1);
        recoveryService.resume(lost.get());
        assertThat(jdbc.queryForObject("SELECT state FROM message_recovery", String.class)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT replay_count FROM message_recovery", Integer.class)).isEqualTo(1);
        assertThat(resumer.resumeDue(at.plusSeconds(600))).isZero();
    }

    /**
     * 模拟已提交重放租约及审计锚点，供重启续跑与过期接管场景使用。
     */
    private void persistCommittedClaim(UUID recovery, Instant lease, UUID attempt, Instant at) {
        jdbc.update("""
                UPDATE message_recovery SET state='REPLAYING', next_attempt_at=?, replay_count=1,
                    last_replayed_at=?,last_replayed_by_type='USER',last_replayed_by_id=?,
                    last_replay_reason='verified original message',last_replay_correlation_id=?,lock_version=lock_version+1
                WHERE recovery_id=?
                """, lease == null ? null : Timestamp.from(lease), Timestamp.from(at),
                NotificationIntegrationFixtures.bytes(NotificationIntegrationFixtures.ADMIN_A),
                NotificationIntegrationFixtures.bytes(attempt), NotificationIntegrationFixtures.bytes(recovery));
    }
}
