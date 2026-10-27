package com.flowdesk.ticket.approval;
import com.flowdesk.ticket.workflow.TicketWorkflowFeignClient;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import feign.FeignException;
import feign.Request;
import feign.Response;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
/**
 * 工单模块回归测试，覆盖该组件的正常流程、边界条件与关键一致性约束。
 */
final class WorkflowFeignTestClient implements TicketWorkflowFeignClient {
    private final RestClient http;
    /**
     * 初始化审批上下文测试替身，保存当前场景使用的可控状态与依赖。
     */
    WorkflowFeignTestClient(String url) { http = RestClient.builder().baseUrl(url).build(); }
    /**
     * 向本地测试 HTTP 服务发送认证 GET 请求，保留关联头并将远程错误映射为 Feign 测试异常。
     */
    private String get(String path, UUID id, String authorization, String correlation) {
        try {
            return http.get().uri(path, id).header("Authorization", authorization)
                .header("X-Correlation-Id", correlation).retrieve().body(String.class);
        } catch (RestClientResponseException error) {
            Request request = Request.create(Request.HttpMethod.GET, "http://test" + path,
                Map.of(), null, StandardCharsets.UTF_8, null);
            throw FeignException.errorStatus("test", Response.builder().request(request)
                .status(error.getStatusCode().value()).reason("fixture")
                .headers(Map.of()).body(error.getResponseBodyAsByteArray()).build());
        }
    }
    /**
     * 读取工作流版本，供目录绑定时验证发布状态与规则快照。
     */
    public String getWorkflowVersion(UUID id, String token, String correlation) { return get("/api/v1/workflow-versions/{id}", id, token, correlation); }
    /**
     * 读取审批任务，供确认当前用户是否为对应审批人。
     */
    public String getApprovalTask(UUID id, String token, String correlation) { return get("/api/v1/approval-tasks/{id}", id, token, correlation); }
    /**
     * 读取工作流实例，核对实例与申请工单的关联关系。
     */
    public String getWorkflowInstance(UUID id, String token, String correlation) { return get("/api/v1/workflow-instances/{id}", id, token, correlation); }
}
