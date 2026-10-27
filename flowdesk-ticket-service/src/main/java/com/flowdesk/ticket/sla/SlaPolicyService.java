package com.flowdesk.ticket.sla;

import com.flowdesk.ticket.dto.TicketDtos;
import com.flowdesk.ticket.service.PageResult;

/**
 * 受当前租户约束的 SLA 策略查询契约，提供分页策略摘要，由实现层负责授权和事务边界。
 */
public interface SlaPolicyService {
    /**
     * 按筛选条件与稳定游标分页查询SLA数据，返回结果及后续页边界。
     */
    PageResult<TicketDtos.SlaPolicySummaryResponse> list(String status, int pageSize,
                                                                String pageToken, String sort);

}
