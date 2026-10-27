package com.flowdesk.ticket.catalog.impl;
import com.flowdesk.ticket.dto.TicketDtos;
import com.flowdesk.ticket.model.CatalogItemRow;

/**
 * 集中转换目录持久化快照与对外响应，统一关联规则引用的表示方式。
 */
final class CatalogResponseMapper {
    /**
     * 私有构造方法，阻止工具类实例化，调用方应使用静态入口。
     */
    private CatalogResponseMapper() { }
    /**
     * 将内部持久化快照转换为对外响应，只暴露接口契约需要的字段。
     */
    static TicketDtos.ServiceCatalogItemResponse toResponse(CatalogItemRow row) {
        TicketDtos.WorkflowRef workflowRef = row.workflowDefinitionId() == null ? null
                : new TicketDtos.WorkflowRef(row.workflowDefinitionId(), row.workflowVersionId(),
                        row.workflowVersionNo());
        return new TicketDtos.ServiceCatalogItemResponse(row.catalogItemId(), row.itemCode(),
                row.itemName(), row.description(), row.riskLevel(), row.requiresApproval(),
                workflowRef, row.slaPolicyId(), row.status(), row.lockVersion(),
                row.createdAt(), row.updatedAt());
    }
}
