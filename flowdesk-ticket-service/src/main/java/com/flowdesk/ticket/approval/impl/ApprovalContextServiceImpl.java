package com.flowdesk.ticket.approval.impl;

import com.flowdesk.ticket.mapper.*;
import com.flowdesk.ticket.model.*;
import com.flowdesk.ticket.persistence.Uuids;
import com.flowdesk.ticket.approval.*;

import com.flowdesk.ticket.mapper.CatalogMapper;
import com.flowdesk.ticket.service.TicketSupport;
import com.flowdesk.common.web.ApiException;
import org.springframework.stereotype.Service;
import java.util.UUID;
/**
 * 协调审批访问校验和工单摘要读取，避免审批权限扩展为工单通用访问权限。
 */
@Service
public class ApprovalContextServiceImpl implements ApprovalContextService {
    private final TicketMapper ticketMapper;
    private final CatalogMapper catalogRepository;
    private final ApprovalAccessChecker checker;
    /**
     * 构造审批上下文组件并保存调用依赖。
     */
    public ApprovalContextServiceImpl(TicketMapper ticketMapper, CatalogMapper catalogRepository, ApprovalAccessChecker checker) {
        this.ticketMapper=ticketMapper; this.catalogRepository=catalogRepository; this.checker=checker;
    }
    /**
     * 读取指定审批上下文详情，并依据当前租户与权限校验资源可见性。
     */
    @Override
    public ApplicationSummary get(UUID ticketId, UUID taskId, String authorization) {
        var context = TicketSupport.context();
        if (!checker.canRead(context.tenantId(), TicketSupport.actorId(), taskId, ticketId, authorization)) {
            throw ApiException.notFound("RESOURCE_NOT_FOUND", "申请信息不可见。");
        }
        var ticket = ticketMapper.findTicket(context.tenantId(), ticketId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "申请信息不可见。"));
        String catalog = ticket.catalogItemId() == null ? null
                : catalogRepository.findCatalogItem(context.tenantId(), ticket.catalogItemId())
                        .map(CatalogItemRow::itemName).orElse(null);
        return new ApplicationSummary(ticket.ticketId(), ticket.ticketNo(),
                ticket.requesterUserId(), ticket.subject(), ticket.description(), ticket.status(),
                catalog, ticket.createdAt());
    }
}
