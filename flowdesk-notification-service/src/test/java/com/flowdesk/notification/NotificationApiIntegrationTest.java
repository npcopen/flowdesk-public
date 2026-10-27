package com.flowdesk.notification;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.notification.event.NotificationConsumeResult;
import com.flowdesk.notification.event.NotificationConsumerService;
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

/**
 * 通知 API 集成测试，验证接收人隔离、读状态版本控制、幂等写入与游标筛选。
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private NotificationConsumerService service;
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 注册当前集成测试所需的隔离数据库、身份密钥和组件配置。
     */
    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        NotificationIntegrationFixtures.properties(registry, "notification-api");
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
     * 验证通知接收人隔离、读状态更新及幂等重放同时生效，并检查相关业务事实或错误响应符合约定。
     */
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
    /**
     * 验证Mapper 游标分页保持接收人范围和已绑定筛选条件，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void mapperCursorPagingKeepsRecipientAndBoundFilters() throws Exception {
        for (int i = 0; i < 3; i++) {
            var delivery = NotificationIntegrationFixtures.delivery(NotificationEventContract.TICKET,
                    NotificationIntegrationFixtures.USER_A, UUID.randomUUID(), "ASSIGNMENT_NOTICE", "notice " + i);
            assertThat(service.consume(delivery.main())).isEqualTo(NotificationConsumeResult.ack("NOTIFICATION_PROCESSED"));
        }
        String auth = "Bearer " + NotificationIntegrationFixtures.userToken(NotificationIntegrationFixtures.USER_A);
        for (String sort : List.of("createdAt", "-createdAt")) {
            var first = mockMvc.perform(get("/api/v1/notifications").header("Authorization", auth)
                    .param("state", "UNREAD").param("notificationKind", "ASSIGNMENT_NOTICE")
                    .param("pageSize", "1").param("sort", sort))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1)).andReturn();
            String token = JsonPath.read(first.getResponse().getContentAsString(), "$.meta.page.nextPageToken");
            String firstId = JsonPath.read(first.getResponse().getContentAsString(), "$.data[0].notificationId");
            var second = mockMvc.perform(get("/api/v1/notifications").header("Authorization", auth)
                    .param("state", "UNREAD").param("notificationKind", "ASSIGNMENT_NOTICE")
                    .param("pageSize", "1").param("sort", sort).param("pageToken", token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1)).andReturn();
            String secondId = JsonPath.read(second.getResponse().getContentAsString(), "$.data[0].notificationId");
            assertThat(secondId).isNotEqualTo(firstId);
        }
    }

}
