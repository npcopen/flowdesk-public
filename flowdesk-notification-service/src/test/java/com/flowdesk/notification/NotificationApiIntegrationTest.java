package com.flowdesk.notification;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.notification.event.NotificationConsumeResult;
import com.flowdesk.notification.service.NotificationService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** M8 recipient isolation, optimistic read state and idempotency API slice. */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private NotificationService service;
    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        NotificationIntegrationFixtures.properties(registry, "notification-api");
    }

    @BeforeEach
    void clean() {
        for (String table : List.of("message_recovery", "audit_record", "inbox_message", "notification")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void recipientIsolationReadStateAndIdempotencyAreEnforced() throws Exception {
        NotificationIntegrationFixtures.Delivery recipientA = NotificationIntegrationFixtures.delivery(
                NotificationEventContract.TICKET, NotificationIntegrationFixtures.USER_A,
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                "ASSIGNMENT_NOTICE", "仅 A 可见");
        NotificationIntegrationFixtures.Delivery recipientB = NotificationIntegrationFixtures.delivery(
                NotificationEventContract.WORKFLOW, NotificationIntegrationFixtures.USER_B,
                UUID.fromString("10000000-0000-0000-0000-000000000002"),
                "APPROVAL_NOTICE", "仅 B 可见");

        assertThat(service.consume(recipientA.main()))
                .isEqualTo(NotificationConsumeResult.ack("NOTIFICATION_PROCESSED"));
        assertThat(service.consume(recipientB.main()))
                .isEqualTo(NotificationConsumeResult.ack("NOTIFICATION_PROCESSED"));

        MvcResult list = mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer "
                                + NotificationIntegrationFixtures.userToken(NotificationIntegrationFixtures.USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("仅 A 可见"))
                .andExpect(jsonPath("$.data[0].state").value("UNREAD"))
                .andReturn();
        UUID notificationA = UUID.fromString(JsonPath.read(list.getResponse().getContentAsString(),
                "$.data[0].notificationId"));

        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header("Authorization", "Bearer "
                                + NotificationIntegrationFixtures.userToken(NotificationIntegrationFixtures.USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(1));

        mockMvc.perform(put("/api/v1/notifications/" + notificationA + "/read-state")
                        .header("Authorization", "Bearer "
                                + NotificationIntegrationFixtures.userToken(NotificationIntegrationFixtures.USER_B))
                        .header("If-Match", "\"lv:0\"")
                        .header("Idempotency-Key", "notification-other-user-0001")
                        .contentType("application/json")
                        .content("{\"state\":\"READ\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOTIFICATION_RECIPIENT_ONLY"));

        mockMvc.perform(put("/api/v1/notifications/" + notificationA + "/read-state")
                        .header("Authorization", "Bearer "
                                + NotificationIntegrationFixtures.userToken(NotificationIntegrationFixtures.USER_A))
                        .header("If-Match", "\"lv:0\"")
                        .header("Idempotency-Key", "notification-read-a-0001")
                        .contentType("application/json")
                        .content("{\"state\":\"READ\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"lv:1\""))
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andExpect(jsonPath("$.data.state").value("READ"))
                .andExpect(jsonPath("$.data.lockVersion").value(1));

        mockMvc.perform(put("/api/v1/notifications/" + notificationA + "/read-state")
                        .header("Authorization", "Bearer "
                                + NotificationIntegrationFixtures.userToken(NotificationIntegrationFixtures.USER_A))
                        .header("If-Match", "\"lv:0\"")
                        .header("Idempotency-Key", "notification-read-a-0001")
                        .contentType("application/json")
                        .content("{\"state\":\"READ\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"lv:1\""))
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.data.lockVersion").value(1));

        mockMvc.perform(put("/api/v1/notifications/" + notificationA + "/read-state")
                        .header("Authorization", "Bearer "
                                + NotificationIntegrationFixtures.userToken(NotificationIntegrationFixtures.USER_A))
                        .header("If-Match", "\"lv:0\"")
                        .header("Idempotency-Key", "notification-read-a-0002")
                        .contentType("application/json")
                        .content("{\"state\":\"UNREAD\"}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_VERSION_CONFLICT"));

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer "
                                + NotificationIntegrationFixtures.userToken(NotificationIntegrationFixtures.USER_A))
                        .param("state", "READ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].notificationId").value(notificationA.toString()));
    }
}
