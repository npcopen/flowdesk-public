package com.flowdesk.workflow.start;

import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/** Strict service-JWT HTTP adapter for IAM API-016. */
final class WorkflowIamEligibilityClient implements WorkflowApproverEligibilityChecker {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient restClient;
    private final WorkflowIamClientProperties properties;

    WorkflowIamEligibilityClient(RestClient.Builder builder,
                                 WorkflowIamClientProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.restClient = builder
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public boolean isEligibleApprover(UUID tenantId, UUID approverUserId) {
        TenantContext context = TenantContextHolder.current();
        context.requireTenant(tenantId);
        String token = readServiceToken(tenantId);
        if (token == null) {
            throw new WorkflowIamUnavailableException();
        }

        String response;
        try {
            response = restClient.post()
                    .uri("/api/v1/internal/iam/eligibility-checks")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Correlation-Id", context.correlationId().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "subjectUserId", approverUserId.toString(),
                            "requiredRoleCode", "APPROVER",
                            "purpose", "WORKFLOW_APPROVER"))
                    .retrieve()
                    .body(String.class);
        }
        catch (RuntimeException dependencyFailure) {
            throw new WorkflowIamUnavailableException(dependencyFailure);
        }

        try {
            JsonNode data = JSON.readTree(response).path("data");
            JsonNode subjectNode = data.get("subjectUserId");
            JsonNode eligibleNode = data.get("eligible");
            JsonNode reasonNode = data.get("reasonCode");
            if (subjectNode == null || !subjectNode.isString()
                    || eligibleNode == null || !eligibleNode.isBoolean()
                    || reasonNode == null || !reasonNode.isString()
                    || !approverUserId.toString().equals(subjectNode.stringValue())) {
                throw new IllegalArgumentException("IAM response shape mismatch");
            }
            boolean eligible = eligibleNode.booleanValue();
            String reason = reasonNode.stringValue();
            if (eligible && !"ELIGIBLE".equals(reason)) {
                throw new IllegalArgumentException("IAM response decision mismatch");
            }
            if (!eligible && reason.isBlank()) {
                throw new IllegalArgumentException("IAM response reason missing");
            }
            return eligible;
        }
        catch (Exception malformedResponse) {
            throw new WorkflowIamUnavailableException(malformedResponse);
        }
    }

    private String readServiceToken(UUID tenantId) {
        String location = properties.getServiceTokenFile();
        if (location == null || location.isBlank()) {
            return null;
        }
        Path path = Path.of(location.replace("{tenantId}", tenantId.toString()));
        try {
            String token = Files.readString(path, StandardCharsets.UTF_8).trim();
            return token.isBlank() ? null : token;
        }
        catch (IOException unreadableToken) {
            return null;
        }
    }
}
