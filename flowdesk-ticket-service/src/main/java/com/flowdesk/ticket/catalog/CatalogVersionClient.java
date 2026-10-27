package com.flowdesk.ticket.catalog;

import com.flowdesk.ticket.dto.CatalogWriteRequest;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.ticket.workflow.TicketWorkflowFeignClient;
import feign.FeignException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/** 校验目录引用的流程版本属于当前租户、已发布且适用于高风险审批。 */
@Component
public class CatalogVersionClient {

    private final TicketWorkflowFeignClient client;

    /**
     * 构造服务目录组件并保存调用依赖。
     */
    public CatalogVersionClient(TicketWorkflowFeignClient client) {
        this.client = client;
    }

    /** 透传当前认证调用流程查询，并逐项核对版本、定义、发布状态及高风险条件。 */
    public void validate(CatalogWriteRequest.WorkflowBinding binding, String authorization) {
        try {
            String body = client.getWorkflowVersion(binding.workflowVersionId(), authorization,
                    TenantContextHolder.current().correlationId().toString());
            var node = new tools.jackson.databind.ObjectMapper().readTree(body).path("data");
            if (!binding.workflowVersionId().toString().equals(node.path("workflowVersionId").asText())
                    || !binding.definitionId().toString().equals(node.path("definitionId").asText())
                    || binding.versionNo() != node.path("versionNo").asInt()
                    || !"PUBLISHED".equals(node.path("status").asText())
                    || !"HIGH".equals(node.path("condition").path("value").asText())) {
                throw invalid();
            }
        } catch (ApiException e) {
            throw e;
        } catch (FeignException e) {
            if (e.status() >= 400 && e.status() < 500) throw invalid();
            throw unavailable();
        } catch (RuntimeException e) {
            throw unavailable();
        }
    }

    /**
     * 构造依赖不可用错误，避免将下游异常误判为缺失资源或授权通过。
     */
    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WORKFLOW_VERSION_UNAVAILABLE",
                "暂时无法校验审批版本，请稍后再保存。", true);
    }

    /**
     * 构造输入不合法的结果，供上层返回约定业务或契约错误。
     */
    private static ApiException invalid() {
        return ApiException.unprocessable("WORKFLOW_VERSION_INVALID",
                "请选择本租户已发布的高风险审批版本。", List.of());
    }
}
