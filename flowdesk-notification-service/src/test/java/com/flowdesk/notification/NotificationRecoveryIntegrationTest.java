package com.flowdesk.notification;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.notification.event.NotificationConsumeResult;
import com.flowdesk.notification.service.NotificationService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** M8 DLQ source persistence and privileged, safe Notification replay slice. */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationRecoveryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private NotificationService service;
    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        NotificationIntegrationFixtures.properties(registry, "notification-recovery");
    }

    @BeforeEach
    void clean() {
        for (String table : List.of("message_recovery", "audit_record", "inbox_message", "notification")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

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

    private UUID recoveryFor(NotificationIntegrationFixtures.Delivery delivery) {
        byte[] value = jdbc.queryForObject("""
                SELECT r.recovery_id FROM message_recovery r
                JOIN inbox_message i ON i.tenant_id=r.tenant_id AND i.inbox_id=r.source_record_id
                WHERE i.tenant_id=? AND i.message_id=?
                """, byte[].class, NotificationIntegrationFixtures.bytes(NotificationIntegrationFixtures.TENANT_A),
                NotificationIntegrationFixtures.bytes(delivery.messageId()));
        return NotificationIntegrationFixtures.uuid(value);
    }
}
