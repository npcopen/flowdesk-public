package com.flowdesk.ticket.iam;

import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client for IAM API-016. The call is performed outside the Ticket write
 * transaction and always fails closed: when IAM or the service identity is unavailable
 * the operation returns 503 IAM_ELIGIBILITY_UNAVAILABLE and writes nothing.
 */
@Component
public class IamEligibilityClient implements AssigneeEligibilityChecker, SlaTargetQualificationChecker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final TicketIamClientProperties properties;

    public IamEligibilityClient(RestClient.Builder builder, TicketIamClientProperties properties) {
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
    public boolean isEligibleAgent(UUID tenantId, UUID userId) {
        Qualification result = check(tenantId, userId, "AGENT", "TICKET_ASSIGNEE");
        return result.eligible() && "ELIGIBLE".equals(result.reasonCode());
    }

    @Override
    public Qualification checkServiceDeskAdmin(UUID tenantId, UUID userId) {
        return check(tenantId, userId, "SERVICE_DESK_ADMIN", "SLA_ESCALATION_TARGET");
    }

    private Qualification check(UUID tenantId, UUID userId, String roleCode, String purpose) {
        if (tenantId == null || userId == null) throw unavailable();
        String token = readServiceToken(tenantId);
        if (token == null) {
            throw unavailable();
        }
        TenantContext context = TenantContextHolder.current();
        context.requireTenant(tenantId);
        String response;
        try {
            response = restClient.post()
                    .uri("/api/v1/internal/iam/eligibility-checks")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Correlation-Id", context.correlationId().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "subjectUserId", userId.toString(),
                            "requiredRoleCode", roleCode,
                            "purpose", purpose))
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException e) {
            throw unavailable();
        }
        try {
            JsonNode root = MAPPER.readTree(response);
            JsonNode data = root.path("data");
            UUID subject = UUID.fromString(data.path("subjectUserId").asText());
            boolean eligible = data.path("eligible").asBoolean(false);
            String reasonCode = data.path("reasonCode").asText("");
            long version = data.path("qualificationVersion").asLong(-1L);
            Instant checkedAt = Instant.parse(data.path("checkedAt").asText());
            if (!userId.equals(subject) || version <= 0 || reasonCode.isBlank()) throw unavailable();
            return new Qualification(subject, eligible, reasonCode, version, checkedAt);
        } catch (Exception e) {
            throw unavailable();
        }
    }

    private String readServiceToken(UUID tenantId) {
        String location = properties.getServiceTokenFile();
        if (location == null || location.isBlank()) {
            return null;
        }
        String resolved = location.replace("{tenantId}", tenantId.toString());
        try {
            String token = Files.readString(Path.of(resolved), StandardCharsets.UTF_8).trim();
            return token.isBlank() ? null : token;
        } catch (IOException e) {
            return null;
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "IAM_ELIGIBILITY_UNAVAILABLE", "IAM 资格校验暂不可用，未执行任何写入。", true);
    }
}
