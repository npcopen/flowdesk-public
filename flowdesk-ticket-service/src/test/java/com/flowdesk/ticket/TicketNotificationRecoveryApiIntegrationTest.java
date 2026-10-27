package com.flowdesk.ticket;

import com.flowdesk.ticket.mapper.*;
import com.flowdesk.ticket.model.*;
import com.flowdesk.ticket.persistence.Uuids;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.RoleCode;
import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.security.JwtIssuer;
import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.ticket.notification.TicketNotificationIntent;
import com.flowdesk.ticket.notification.TicketNotificationOutboxMessage;
import com.flowdesk.ticket.notification.TicketNotificationOutboxPublisher;
import com.flowdesk.ticket.notification.TicketNotificationDispatchService;
import com.flowdesk.ticket.notification.TicketNotificationOutboxSendResult;
import com.flowdesk.ticket.persistence.Uuids;
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
 * 工单模块回归测试，覆盖该组件的正常流程、边界条件与关键一致性约束。
 */
@SpringBootTest(properties = {
        "flowdesk.ticket.workflow-reverse.entry.enabled=true",
        "flowdesk.ticket.workflow-reverse.consumer.enabled=false"
})
@AutoConfigureMockMvc
class TicketNotificationRecoveryApiIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final KeyPair KEYS = keyPair();
    private static final JwtIssuer ISSUER = new JwtIssuer(KEYS.getPrivate(),
            "flowdesk-iam-service", "flowdesk-api", "flowdesk-internal", 900, 300);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TicketNotificationDispatchService repository;
    @Autowired com.flowdesk.ticket.notification.TicketNotificationOutboxWriter writer;
    @Autowired PlatformTransactionManager manager;

    /**
     * 注册隔离测试数据库、密钥和功能开关，使场景在可控环境运行。
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("flowdesk.security.jwt.public-key-pem", () -> "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----");
        registry.add("flowdesk.security.cursor-token.hmac-key", () -> Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        registry.add("FLOWDESK_TICKET_DB_PASSWORD", () -> "");
        registry.add("spring.cloud.service-registry.auto-registration.enabled", () -> "false");
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:ticket-notification-recovery;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:schema-test.sql");
    }

    /**
     * 验证通知重放失败后可再受理并发布，原业务事实不变。
     */
    @Test
    void exhaustedNotificationCanBeReplayedAgainAfterFailureAndPublishedWithoutChangingItsFact() throws Exception {
        TestClock clock = new TestClock(Instant.now().minus(Duration.ofDays(1)));
        AtomicBoolean brokerAvailable = new AtomicBoolean();
        List<TicketNotificationOutboxMessage> deliveries = new ArrayList<>();
        var publisher = new TicketNotificationOutboxPublisher(repository, message -> {
            deliveries.add(message);
            return brokerAvailable.get() ? TicketNotificationOutboxSendResult.sendOk()
                    : TicketNotificationOutboxSendResult.notConfirmed("BROKER_SEND_FAILED", "Broker unavailable");
        }, manager, clock);
        var intent = TicketNotificationIntent.create(TENANT, ADMIN, TicketNotificationIntent.ASSIGNED,
                new byte[32], "TICKET", UUID.randomUUID(), "工单已分配", "请处理工单。",
                UUID.randomUUID(), UUID.randomUUID(), clock.instant());
        try (TenantContextScope ignored = TenantContextScope.open(TenantContext.ofPersistedTask(
                TENANT, ActorRef.service("flowdesk-ticket-service"), intent.correlationId()))) {
            new TransactionTemplate(manager).executeWithoutResult(tx -> writer.write(TENANT, intent));
        }

        exhaust(publisher, clock, intent.outboxId());
        UUID recovery = Uuids.toUuid(jdbc.queryForObject(
                "SELECT recovery_id FROM message_recovery WHERE tenant_id=? AND source_record_id=?",
                byte[].class, Uuids.toBytes(TENANT), Uuids.toBytes(intent.outboxId())));
        assertRecovery(recovery, "OPEN", 0);
        replay(recovery, "ticket-notification-retry-1", false);
        replay(recovery, "ticket-notification-retry-1", true);
        assertRecovery(recovery, "REPLAYING", 1);

        clock.at(Instant.now().plusSeconds(1));
        exhaust(publisher, clock, intent.outboxId());
        assertRecovery(recovery, "OPEN", 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_recovery WHERE tenant_id=?",
                Integer.class, Uuids.toBytes(TENANT))).isEqualTo(1);

        replay(recovery, "ticket-notification-retry-2", false);
        brokerAvailable.set(true);
        assertThat(publisher.publishOnce().published()).isEqualTo(1);
        assertRecovery(recovery, "RESOLVED", 2);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE tenant_id=? AND outbox_id=?",
                String.class, Uuids.toBytes(TENANT), Uuids.toBytes(intent.outboxId()))).isEqualTo("PUBLISHED");
        assertThat(publisher.publishOnce().scanned()).isZero();
        assertThat(deliveries).hasSize(17).allSatisfy(message -> {
            assertThat(message.keys()).isEqualTo(intent.messageId().toString());
            assertThat(message.body()).containsExactly(intent.bodyBytes());
            assertThat(message.userProperties()).isEqualTo(deliveries.get(0).userProperties());
        });
    }

    /**
     * 重复执行发送至耗尽阈值，构造可由恢复 API 重放的通知事实。
     */
    private void exhaust(TicketNotificationOutboxPublisher publisher, TestClock clock, UUID outboxId) {
        for (int attempt = 1; attempt <= NotificationEventContract.OUTBOX_MAX_ATTEMPTS; attempt++) {
            var result = publisher.publishOnce();
            assertThat(result.claimedForSend()).isEqualTo(1);
            if (attempt == NotificationEventContract.OUTBOX_MAX_ATTEMPTS) assertThat(result.recovered()).isEqualTo(1);
            else {
                assertThat(result.rescheduled()).isEqualTo(1);
                Timestamp next = jdbc.queryForObject(
                        "SELECT next_attempt_at FROM outbox_event WHERE tenant_id=? AND outbox_id=?",
                        Timestamp.class, Uuids.toBytes(TENANT), Uuids.toBytes(outboxId));
                clock.at(next.toInstant().plusMillis(1));
            }
        }
    }

    /**
     * 先读取恢复项 ETag，再经 HTTP 受理重放，核验幂等响应标记。
     */
    private void replay(UUID recovery, String key, boolean replayed) throws Exception {
        String etag = mockMvc.perform(get("/api/v1/ticket-message-recoveries/" + recovery)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        mockMvc.perform(post("/api/v1/ticket-message-recoveries/" + recovery + "/replays")
                        .header("Authorization", bearer()).header("If-Match", etag)
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"reason\":\"重新发送原始通知\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotency-Replayed", Boolean.toString(replayed)))
                .andExpect(jsonPath("$.data.state").value("REPLAYING"));
    }

    /**
     * 核对恢复项状态、来源和数量，保证重复执行未产生多余记录。
     */
    private void assertRecovery(UUID recovery, String state, int replayCount) throws Exception {
        mockMvc.perform(get("/api/v1/ticket-message-recoveries/" + recovery).header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value(state))
                .andExpect(jsonPath("$.data.replayCount").value(replayCount));
    }

    /**
     * 构造请求使用的 Bearer 认证信息，供 API 权限与租户测试。
     */
    private String bearer() {
        return "Bearer " + ISSUER.issueHuman(TENANT, ADMIN, Set.of(RoleCode.SERVICE_DESK_ADMIN),
                Set.of(PermissionCode.RECOVERY_READ_TENANT, PermissionCode.RECOVERY_REPLAY_TENANT)).token();
    }

    /**
     * 生成测试专用签名密钥对，供构造认证令牌。
     */
    private static KeyPair keyPair() {
        try { return KeyPairGenerator.getInstance("RSA").generateKeyPair(); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    /**
     * 用于该测试的内部替身，提供可控制的时钟、调度或依赖行为。
     */
    private static final class TestClock extends Clock {
        private Instant at;
        /**
         * 初始化工单测试替身，保存当前场景使用的可控状态与依赖。
         */
        TestClock(Instant at) { this.at = at.truncatedTo(ChronoUnit.MICROS); }
        /**
         * 推进测试可变时钟到指定时点，精确覆盖租约和重试到期边界。
         */
        void at(Instant value) { at = value.truncatedTo(ChronoUnit.MICROS); }
        /**
         * 返回测试时钟使用的时区。
         */
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        /**
         * 按测试时钟约定返回指定时区视图，保持可控时间不变。
         */
        @Override public Clock withZone(ZoneId zone) { return this; }
        /**
         * 取得当前规范精度时间。
         */
        @Override public Instant instant() { return at; }
    }
}
