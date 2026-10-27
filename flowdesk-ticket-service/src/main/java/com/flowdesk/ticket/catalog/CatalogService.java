package com.flowdesk.ticket.catalog;

import com.flowdesk.ticket.dto.TicketDtos;
import com.flowdesk.ticket.service.PageResult;
import java.util.UUID;

/**
 * 服务目录查询与局部更新契约，由实现层负责可见性、权限、版本校验及事务组织。
 */
public interface CatalogService {
    /**
     * 按筛选条件与稳定游标分页查询服务目录数据，返回结果及后续页边界。
     */
    PageResult<TicketDtos.ServiceCatalogItemResponse> list(String status, String riskLevel,
                                                                  int pageSize, String pageToken,
                                                                  String sort);

    /**
     * 读取指定服务目录详情，并依据当前租户与权限校验资源可见性。
     */
    TicketDtos.ServiceCatalogItemResponse get(UUID catalogItemId);

    /**
     * 执行目录局部更新，区分字段缺省和显式值，并以 If-Match 防止覆盖并发修改。
     */
    TicketDtos.ServiceCatalogItemResponse patch(UUID catalogItemId,
                                                       TicketDtos.ServiceCatalogPatchRequest request,
                                                       long expectedLockVersion);

}
