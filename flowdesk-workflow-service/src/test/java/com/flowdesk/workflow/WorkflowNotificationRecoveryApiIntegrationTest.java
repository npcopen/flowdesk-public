package com.flowdesk.workflow;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.RoleCode;
import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.security.JwtIssuer;
import com.flowdesk.workflow.notification.WorkflowNotificationIntent;
import com.flowdesk.workflow.notification.WorkflowNotificationOutboxMessage;
import com.flowdesk.workflow.notification.WorkflowNotificationOutboxPublisher;
import com.flowdesk.workflow.service.WorkflowNotificationOutboxService;
import com.flowdesk.workflow.notification.WorkflowNotificationOutboxSendResult;
import com.flowdesk.workflow.notification.WorkflowNotificationOutboxWriter;
import com.flowdesk.workflow.service.WorkflowIdempotencyCodec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通知及反向事件恢复 API 集成测试，核验原事实不变、失败后可再重放和租户读取范围。
 */
@SpringBootTest(properties = {
        "flowdesk.workflow.start.entry.enabled=true",
        "flowdesk.workflow.start.consumer.enabled=false"
})
@AutoConfigureMockMvc
class WorkflowNotificationRecoveryApiIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final KeyPair KEYS = keyPair();
    private static final JwtIssuer ISSUER = new JwtIssuer(KEYS.getPrivate(),
            "flowdesk-iam-service", "flowdesk-api", "flowdesk-internal", 900, 300);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkflowNotificationOutboxWriter writer;
    @Autowired WorkflowNotificationOutboxService repository;
    @Autowired PlatformTransactionManager manager;

    /**
     * 注册测试专用数据库、密钥和开关配置，使应用上下文使用隔离依赖。
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.security.jwt.public-key-pem", () -> "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----");
        registry.add("flowdesk.security.cursor-token.hmac-key", () -> Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        registry.add("FLOWDESK_WORKFLOW_DB_PASSWORD", () -> "");
        registry.add("spring.cloud.service-registry.auto-registration.enabled", () -> "false");
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:workflow-notification-recovery;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:schema-test.sql");
    }

    /**
     * 验证耗尽通知重放失败后可再次重放并发布且原事实不变，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void exhaustedNotificationCanBeReplayedAgainAfterFailureAndPublishedWithoutChangingItsFact() throws Exception {
        TestClock clock = new TestClock(Instant.now().minus(Duration.ofDays(1)));
        AtomicBoolean brokerAvailable = new AtomicBoolean();
        List<WorkflowNotificationOutboxMessage> deliveries = new ArrayList<>();
        var publisher = new WorkflowNotificationOutboxPublisher(repository, message -> {
            deliveries.add(message);
            return brokerAvailable.get() ? WorkflowNotificationOutboxSendResult.sendOk()
                    : WorkflowNotificationOutboxSendResult.notConfirmed("BROKER_SEND_FAILED", "Broker unavailable");
        }, clock);
        var intent = WorkflowNotificationIntent.create(TENANT, ADMIN, UUID.randomUUID(), UUID.randomUUID(),
                1, UUID.randomUUID(), UUID.randomUUID(), clock.instant());
        new TransactionTemplate(manager).executeWithoutResult(tx -> writer.write(TENANT, intent));

        exhaust(publisher, clock, intent.outboxId());
        UUID recovery = uuid(jdbc.queryForObject(
                "SELECT recovery_id FROM message_recovery WHERE tenant_id=? AND source_record_id=?",
                byte[].class, bytes(TENANT), bytes(intent.outboxId())));
        assertRecovery(recovery, "OPEN", 0);
        replay(recovery, "workflow-notification-retry-1", false);
        replay(recovery, "workflow-notification-retry-1", true);
        assertRecovery(recovery, "REPLAYING", 1);

        clock.at(Instant.now().plusSeconds(1));
        exhaust(publisher, clock, intent.outboxId());
        assertRecovery(recovery, "OPEN", 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_recovery WHERE tenant_id=?",
                Integer.class, bytes(TENANT))).isEqualTo(1);

        replay(recovery, "workflow-notification-retry-2", false);
        brokerAvailable.set(true);
        assertThat(publisher.publishOnce().published()).isEqualTo(1);
        assertRecovery(recovery, "RESOLVED", 2);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE tenant_id=? AND outbox_id=?",
                String.class, bytes(TENANT), bytes(intent.outboxId()))).isEqualTo("PUBLISHED");
        assertThat(publisher.publishOnce().scanned()).isZero();
        assertThat(deliveries).hasSize(17).allSatisfy(message -> {
            assertThat(message.keys()).isEqualTo(intent.messageId().toString());
            assertThat(message.body()).containsExactly(intent.bodyBytes());
            assertThat(message.userProperties()).isEqualTo(deliveries.get(0).userProperties());
        });
    }

    /**
     * 推进可控时钟并重复发布至自动尝试上限，得到可人工重放的恢复来源。
     */
    private void exhaust(WorkflowNotificationOutboxPublisher publisher, TestClock clock, UUID outboxId) {
        for (int attempt = 1; attempt <= NotificationEventContract.OUTBOX_MAX_ATTEMPTS; attempt++) {
            var result = publisher.publishOnce();
            assertThat(result.claimedForSend()).isEqualTo(1);
            if (attempt == NotificationEventContract.OUTBOX_MAX_ATTEMPTS) assertThat(result.recovered()).isEqualTo(1);
            else {
                assertThat(result.rescheduled()).isEqualTo(1);
                Timestamp next = jdbc.queryForObject(
                        "SELECT next_attempt_at FROM outbox_event WHERE tenant_id=? AND outbox_id=?",
                        Timestamp.class, bytes(TENANT), bytes(outboxId));
                clock.at(next.toInstant().plusMillis(1));
            }
        }
    }

    /**
     * 调用人工重放 API，核对资源版本、幂等响应及原始来源不变。
     */
    private void replay(UUID recovery, String key, boolean replayed) throws Exception {
        String etag = mockMvc.perform(get("/api/v1/workflow-message-recoveries/" + recovery)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        mockMvc.perform(post("/api/v1/workflow-message-recoveries/" + recovery + "/replays")
                        .header("Authorization", bearer()).header("If-Match", etag)
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"reason\":\"重新发送原始通知\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotency-Replayed", Boolean.toString(replayed)))
                .andExpect(jsonPath("$.data.state").value("REPLAYING"));
    }

    /**
     * 断言当前场景的恢复记录状态及重放次数，失败时直接报告对应证据差异。
     */
    private void assertRecovery(UUID recovery, String state, int replayCount) throws Exception {
        mockMvc.perform(get("/api/v1/workflow-message-recoveries/" + recovery).header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value(state))
                .andExpect(jsonPath("$.data.replayCount").value(replayCount));
    }

    /**
     * 为测试令牌添加 Bearer 认证前缀，供真实 HTTP 安全过滤链读取。
     */
    private String bearer() {
        return "Bearer " + ISSUER.issueHuman(TENANT, ADMIN, Set.of(RoleCode.SERVICE_DESK_ADMIN),
                Set.of(PermissionCode.RECOVERY_READ_TENANT, PermissionCode.RECOVERY_REPLAY_TENANT)).token();
    }

    /**
     * 将 UUID 编码为数据库使用的二进制值，空标识保持为空。
     */
    private static byte[] bytes(UUID value) { return WorkflowIdempotencyCodec.uuidBytes(value); }
    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    private static UUID uuid(byte[] value) {
        var buffer = java.nio.ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
    /**
     * 生成测试专用 RSA 密钥对，供真实 JWT 编码和认证断言复用。
     */
    private static KeyPair keyPair() {
        try { return KeyPairGenerator.getInstance("RSA").generateKeyPair(); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    /**
     * 可由测试推进的时钟，供重试、租约过期和恢复续跑场景使用确定性时间。
     */
    private static final class TestClock extends Clock {
        private Instant at;
        /**
         * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
         */
        TestClock(Instant at) { this.at = at.truncatedTo(ChronoUnit.MICROS); }
        /**
         * 将测试时钟设到指定时间点，并匹配数据库比较精度。
         */
        void at(Instant value) { at = value.truncatedTo(ChronoUnit.MICROS); }
        /**
         * 返回测试时钟的固定 UTC 时区，避免本地时区影响时间边界。
         */
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        /**
         * 按测试时钟支持的时区契约返回时钟，保持可控时间点不变。
         */
        @Override public Clock withZone(ZoneId zone) { return this; }
        /**
         * 返回测试时钟当前时间，供租约及重试场景确定性推进。
         */
        @Override public Instant instant() { return at; }
    }
}
