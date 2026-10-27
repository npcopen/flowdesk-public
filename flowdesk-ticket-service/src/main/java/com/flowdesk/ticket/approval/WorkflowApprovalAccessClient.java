package com.flowdesk.ticket.approval;

import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.ticket.workflow.TicketWorkflowFeignClient;
import feign.FeignException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;

/** 调用流程服务核实审批任务、工单和当前用户的关联，依赖不可用时拒绝暴露申请信息。 */
@Component
public class WorkflowApprovalAccessClient implements ApprovalAccessChecker {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TicketWorkflowFeignClient client;

    /**
     * 构造审批上下文组件并保存调用依赖。
     */
    public WorkflowApprovalAccessClient(TicketWorkflowFeignClient client) {
        this.client = client;
    }

    /**
     * 核验认证用户是否能读取指定审批上下文，避免关联查询扩大工单访问权限。
     * 传入租户必须与当前上下文一致。
     */
    @Override
    public boolean canRead(UUID tenantId, UUID actorId, UUID taskId, UUID ticketId, String authorization) {
        var context = TenantContextHolder.current();
        context.requireTenant(tenantId);
        if (authorization == null || !authorization.startsWith("Bearer ")) return false;
        try {
            String correlationId = context.correlationId().toString();
            JsonNode task = data(client.getApprovalTask(taskId, authorization, correlationId));
            if (!taskId.toString().equals(task.path("approvalTaskId").asText())
                    || !actorId.toString().equals(task.path("approverUserId").asText())) return false;
            UUID instanceId = UUID.fromString(task.path("workflowInstanceId").asText());
            JsonNode instance = data(client.getWorkflowInstance(instanceId, authorization, correlationId));
            return instanceId.toString().equals(instance.path("workflowInstanceId").asText())
                    && ticketId.toString().equals(instance.path("ticketId").asText());
        } catch (FeignException failure) {
            if (failure.status() == 403 || failure.status() == 404) return false;
            throw unavailable();
        } catch (RuntimeException failure) { throw unavailable(); }
    }

    /**
     * 提取下游响应业务数据，拒绝缺失或结构不符的信封。
     */
    private static JsonNode data(String body) {
        return JSON.readTree(body).path("data");
    }

    /**
     * 构造依赖不可用错误，避免将下游异常误判为缺失资源或授权通过。
     */
    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "APPROVAL_CONTEXT_UNAVAILABLE",
                "暂时无法确认审批任务，请稍后重新加载申请信息。", true);
    }
}
