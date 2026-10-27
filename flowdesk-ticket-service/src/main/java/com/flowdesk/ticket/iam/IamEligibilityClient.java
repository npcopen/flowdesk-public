package com.flowdesk.ticket.iam;

import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * IAM API-016 的业务适配层，在工单写事务外校验用户资格。
 * HTTP 传输交给声明式 Feign 接口；本类保留令牌轮换、租户核对和响应语义校验。
 * IAM 或服务身份不可用时返回 503 IAM_ELIGIBILITY_UNAVAILABLE，避免写入未经校验的工单变更。
 */
@Component
public class IamEligibilityClient implements AssigneeEligibilityChecker, SlaTargetQualificationChecker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IamEligibilityFeignClient client;
    private final TicketIamClientProperties properties;

    /**
     * 构造IAM 资格组件并保存调用依赖。
     */
    public IamEligibilityClient(IamEligibilityFeignClient client, TicketIamClientProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * 向 IAM 核验当前租户的目标处理人资格，供分派或转派前置检查。
     */
    @Override
    public boolean isEligibleAgent(UUID tenantId, UUID userId) {
        Qualification result = check(tenantId, userId, "AGENT", "TICKET_ASSIGNEE");
        return result.eligible() && "ELIGIBLE".equals(result.reasonCode());
    }

    /**
     * 核验目标用户的服务台管理员资格，供 SLA 升级收件人校验。
     */
    @Override
    public Qualification checkServiceDeskAdmin(UUID tenantId, UUID userId) {
        return check(tenantId, userId, "SERVICE_DESK_ADMIN", "SLA_ESCALATION_TARGET");
    }

    /**
     * 调用资格校验接口并解析结果，供写入前确认目标用户资格。
     * 传入租户必须与当前上下文一致。
     */
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
            response = client.check(
                    "Bearer " + token, context.correlationId().toString(),
                    new IamEligibilityFeignClient.EligibilityRequest(userId, roleCode, purpose));
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

    /**
     * 从配置文件读取服务访问令牌，拒绝缺失或空令牌，避免发起未认证请求。
     */
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

    /**
     * 构造依赖不可用错误，避免将下游异常误判为缺失资源或授权通过。
     */
    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "IAM_ELIGIBILITY_UNAVAILABLE", "IAM 资格校验暂不可用，未执行任何写入。", true);
    }
}
