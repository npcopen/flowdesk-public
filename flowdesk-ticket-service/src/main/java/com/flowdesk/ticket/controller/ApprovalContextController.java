package com.flowdesk.ticket.controller;

import com.flowdesk.ticket.approval.*;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.web.*;
import org.springframework.web.bind.annotation.*;
import com.flowdesk.ticket.approval.ApprovalContextService.ApplicationSummary;
import java.util.UUID;

/** 为有权处理指定审批任务的用户提供申请摘要，避免通过工单标识绕过审批可见性限制。 */
@RestController
public class ApprovalContextController {
    private final ApprovalContextService service;
    /**
     * 构造工单组件并保存调用依赖。
     */
    public ApprovalContextController(ApprovalContextService service) { this.service = service; }

    /** 先由流程服务核实当前用户与任务的真实分配关系，再读取同租户工单的申请摘要。 */
    @GetMapping("/api/v1/tickets/{ticketId}/approval-context/{taskId}")
    @RequiresPermission(PermissionCode.APPROVAL_READ_ASSIGNED)
    public SuccessEnvelope<ApplicationSummary> get(@PathVariable UUID ticketId, @PathVariable UUID taskId,
            @RequestHeader("Authorization") String authorization) {
        return new SuccessEnvelope<>(service.get(ticketId, taskId, authorization),
                new ApiMeta(RequestCorrelation.current()));
    }

}
