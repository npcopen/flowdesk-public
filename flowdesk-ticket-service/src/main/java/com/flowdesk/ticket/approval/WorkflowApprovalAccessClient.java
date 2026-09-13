package com.flowdesk.ticket.approval;

import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;

@Component
public class WorkflowApprovalAccessClient implements ApprovalAccessChecker {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RestClient client;

    public WorkflowApprovalAccessClient(RestClient.Builder builder,
            @Value("${flowdesk.ticket.workflow.base-url:http://127.0.0.1:8083}") String baseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public boolean canRead(UUID tenantId, UUID actorId, UUID taskId, UUID ticketId, String authorization) {
        var context = TenantContextHolder.current();
        context.requireTenant(tenantId);
        if (authorization == null || !authorization.startsWith("Bearer ")) return false;
        try {
            JsonNode task = get("/api/v1/approval-tasks/" + taskId, authorization);
            if (!taskId.toString().equals(task.path("approvalTaskId").asText())
                    || !actorId.toString().equals(task.path("approverUserId").asText())) return false;
            UUID instanceId = UUID.fromString(task.path("workflowInstanceId").asText());
            JsonNode instance = get("/api/v1/workflow-instances/" + instanceId, authorization);
            return instanceId.toString().equals(instance.path("workflowInstanceId").asText())
                    && ticketId.toString().equals(instance.path("ticketId").asText());
        } catch (HttpClientErrorException failure) {
            if (failure.getStatusCode().value() == 403 || failure.getStatusCode().value() == 404) return false;
            throw unavailable();
        } catch (RuntimeException failure) { throw unavailable(); }
    }

    private JsonNode get(String path, String authorization) {
        String body = client.get().uri(path).header("Authorization", authorization)
                .header("X-Correlation-Id", TenantContextHolder.current().correlationId().toString())
                .retrieve().body(String.class);
        return JSON.readTree(body).path("data");
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "APPROVAL_CONTEXT_UNAVAILABLE",
                "暂时无法确认审批任务，请稍后重新加载申请信息。", true);
    }
}
