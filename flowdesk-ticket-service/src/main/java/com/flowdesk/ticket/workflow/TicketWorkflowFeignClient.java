package com.flowdesk.ticket.workflow;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import java.util.UUID;

/**
 * 工单服务查询流程版本和审批关联的声明式 HTTP 接口。
 * 默认按服务名发现 Workflow，配置 base-url 时仅覆盖本客户端的地址。
 * 当前用户身份与关联编号逐请求传递，原始 JSON 由业务适配层继续校验。
 */
@FeignClient(
        name = "flowdesk-workflow-service",
        contextId = "ticketWorkflow",
        url = "${flowdesk.ticket.workflow.base-url:}",
        configuration = TicketWorkflowFeignConfiguration.class)
public interface TicketWorkflowFeignClient {

    /**
     * 读取工作流版本，供目录绑定时验证发布状态与规则快照。
     */
    @GetMapping(value = "/api/v1/workflow-versions/{workflowVersionId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    String getWorkflowVersion(
            @PathVariable("workflowVersionId") UUID workflowVersionId,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("X-Correlation-Id") String correlationId);

    /**
     * 读取审批任务，供确认当前用户是否为对应审批人。
     */
    @GetMapping(value = "/api/v1/approval-tasks/{approvalTaskId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    String getApprovalTask(
            @PathVariable("approvalTaskId") UUID approvalTaskId,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("X-Correlation-Id") String correlationId);

    /**
     * 读取工作流实例，核对实例与申请工单的关联关系。
     */
    @GetMapping(value = "/api/v1/workflow-instances/{workflowInstanceId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    String getWorkflowInstance(
            @PathVariable("workflowInstanceId") UUID workflowInstanceId,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("X-Correlation-Id") String correlationId);
}
