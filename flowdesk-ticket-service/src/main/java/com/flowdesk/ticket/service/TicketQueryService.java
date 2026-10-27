package com.flowdesk.ticket.service;

import com.flowdesk.ticket.dto.TicketDtos;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 工单只读用例的统一入口，提供工单详情、列表以及关联的评论、时间线和异步处理状态。
 * 实现层负责根据当前租户和用户权限收敛可见范围，调用方不能通过查询条件绕过访问控制。
 */
public interface TicketQueryService {
    /**
     * 按状态、来源、目录和创建时间筛选当前用户可见的工单，并使用签名游标连续分页。
     * 游标与查询范围、排序及页大小绑定，改变筛选条件后应从第一页重新查询。
     */
    PageResult<TicketDtos.TicketResponse> list(String status, String originType,
                                                      UUID catalogItemId, Instant createdFrom,
                                                      Instant createdTo, String scope,
                                                      int pageSize, String pageToken, String sort);

    /** 统计当前用户权限范围内的待处理工单数量，用于工作台摘要。 */
    Map<String, Long> summary();

    /** 根据 SLA 时钟查找所属工单，并再次校验该工单对当前用户是否可见。 */
    Map<String, UUID> ticketForSlaClock(UUID clockId);

    /** 返回指定工单的对外详情；资源不存在或不在可见范围内时由实现层拒绝访问。 */
    TicketDtos.TicketResponse get(UUID ticketId);

    /** 校验工单可见性后，按创建时间和游标分页读取评论，避免评论接口泄露其他工单内容。 */
    PageResult<TicketDtos.TicketCommentResponse> comments(UUID ticketId, int pageSize,
                                                                 String pageToken, String sort);

    /** 按事件类型和发生时间筛选工单时间线，并以稳定排序的游标返回后续记录。 */
    PageResult<TicketDtos.TimelineEntryResponse> timeline(UUID ticketId, String eventType,
                                                                 Instant occurredFrom, Instant occurredTo,
                                                                 int pageSize, String pageToken,
                                                                 String sort);

    /** 汇总可见工单的 SLA 时钟状态，供详情页展示响应、解决目标及超时情况。 */
    TicketDtos.SlaStatusResponse slaStatus(UUID ticketId);

    /** 读取工作流启动投递及关联结果，帮助前端区分处理中、启动成功和待恢复状态。 */
    TicketDtos.WorkflowStartStatusResponse workflowStartStatus(UUID ticketId);
}
