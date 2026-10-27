package com.flowdesk.workflow.start;

import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 使用服务 JWT 调用 IAM API-016，严格区分资格不满足与暂时无法确定资格。
 * HTTP 传输交给独立的 Feign 客户端；本类保留逐请求令牌读取、租户核对、响应校验及异常语义。
 */
final class WorkflowIamEligibilityClient implements WorkflowApproverEligibilityChecker {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WorkflowIamEligibilityFeignClient client;
    private final WorkflowIamClientProperties properties;

    /**
     * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
     */
    WorkflowIamEligibilityClient(WorkflowIamEligibilityFeignClient client,
                                 WorkflowIamClientProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * 查询目标用户的租户内审批资格，调用路径区分不合格与暂时依赖故障。
     */
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
            response = client.check(
                    "Bearer " + token, context.correlationId().toString(),
                    new WorkflowIamEligibilityFeignClient.EligibilityRequest(
                            approverUserId.toString(), "APPROVER", "WORKFLOW_APPROVER"));
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

    /**
     * 读取配置文件的服务 JWT，供 IAM 请求携带当前服务身份。
     */
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
