package com.flowdesk.ticket.approval;

import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.common.web.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class WorkflowApprovalAccessClientTest {
    private final UUID tenant = UUID.randomUUID(), actor = UUID.randomUUID(), task = UUID.randomUUID(),
            instance = UUID.randomUUID(), ticket = UUID.randomUUID();
    private HttpServer server;
    private WorkflowApprovalAccessClient client;
    private TenantContextScope scope;
    private int status = 200;
    private UUID assignedActor;
    private UUID linkedTicket;
    private final List<String> tokens = new ArrayList<>();

    @BeforeEach void start() throws Exception {
        assignedActor = actor; linkedTicket = ticket;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", exchange -> {
            tokens.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String data = exchange.getRequestURI().getPath().contains("approval-tasks")
                    ? "{\"approvalTaskId\":\"" + task + "\",\"approverUserId\":\"" + assignedActor
                      + "\",\"workflowInstanceId\":\"" + instance + "\"}"
                    : "{\"workflowInstanceId\":\"" + instance + "\",\"ticketId\":\"" + linkedTicket + "\"}";
            byte[] body = ("{\"data\":" + data + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = new WorkflowApprovalAccessClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort());
        scope = TenantContextScope.open(TenantContext.ofHumanJwt(tenant, actor, UUID.randomUUID()));
    }

    @AfterEach void stop() { scope.close(); server.stop(0); }

    @Test void forwardsTheVerifiedHumanIdentityAndChecksBothReferences() {
        assertThat(client.canRead(tenant, actor, task, ticket, "Bearer test-identity")).isTrue();
        assertThat(tokens).containsExactly("Bearer test-identity", "Bearer test-identity");
        linkedTicket = UUID.randomUUID();
        assertThat(client.canRead(tenant, actor, task, ticket, "Bearer test-identity")).isFalse();
    }

    @Test void deniesAnotherApproverOrTaskWithoutReadingTheInstance() {
        assignedActor = UUID.randomUUID();
        assertThat(client.canRead(tenant, actor, task, ticket, "Bearer test-identity")).isFalse();
        assertThat(tokens).hasSize(1);
        assignedActor = actor;
        assertThat(client.canRead(tenant, actor, UUID.randomUUID(), ticket, "Bearer test-identity")).isFalse();
        assertThat(tokens).hasSize(2);
    }

    @Test void dependencyFailuresDoNotGrantAccessOrMasqueradeAsMissingApplications() {
        for (int denied : new int[]{403, 404}) {
            status = denied;
            assertThat(client.canRead(tenant, actor, task, ticket, "Bearer test-identity")).isFalse();
        }
        status = 503;
        assertThatThrownBy(() -> client.canRead(tenant, actor, task, ticket, "Bearer test-identity"))
                .isInstanceOf(ApiException.class).hasMessageContaining("重新加载申请信息");
    }

    @Test void rejectsMissingCredentialsAndDifferentTenantsBeforeAnyRequest() {
        assertThat(client.canRead(tenant, actor, task, ticket, null)).isFalse();
        assertThatThrownBy(() -> client.canRead(UUID.randomUUID(), actor, task, ticket, "Bearer test-identity"))
                .isInstanceOf(RuntimeException.class);
        assertThat(tokens).isEmpty();
    }
}
