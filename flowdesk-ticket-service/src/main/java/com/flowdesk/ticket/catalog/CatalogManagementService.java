package com.flowdesk.ticket.catalog;

import com.flowdesk.ticket.dto.CatalogWriteRequest;
import com.flowdesk.ticket.dto.TicketDtos;
import java.util.UUID;

/**
 * 目录创建和规则绑定契约，组织风险级别、工作流版本及 SLA 策略的写入校验。
 * 实现层负责授权检查和事务边界，调用方通过本契约执行业务用例。
 */
public interface CatalogManagementService {
    /**
     * 校验服务目录创建请求并保存事实，按接口约定处理幂等及版本条件。
     */
    Result create(CatalogWriteRequest request, String key, String authorization);

    /**
     * 校验目录风险、工作流版本与 SLA 绑定，按预期版本更新仅影响未来工单的配置。
     */
    TicketDtos.ServiceCatalogItemResponse configure(UUID id, CatalogWriteRequest request,
                                                           long version, String authorization);

    /**
     * 目录管理写入结果，携带目录响应与本次操作状态。
     */
    public record Result(TicketDtos.ServiceCatalogItemResponse item, boolean replayed) {
    }
}
