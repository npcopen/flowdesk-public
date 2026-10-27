package com.flowdesk.alert.service;

import com.flowdesk.alert.dto.AlertDtos;
import java.util.List;
import java.util.UUID;
import com.flowdesk.alert.model.EventFilter;
import com.flowdesk.alert.model.IncidentFilter;

/** 业务用例契约；HTTP 层只依赖接口。 */
public interface AlertQueryService {
    /**
     * 校验筛选条件与排序后分页查询当前租户的告警事件，返回响应视图和绑定本次条件的下一页游标。
     */
    Page<AlertDtos.AlertEventView> events(EventFilter filter, String sort, int size, String token);
    /**
     * 读取同租户事件详情，返回接纳结论及正文摘要，避免暴露原始敏感报文。
     */
    AlertDtos.AlertEventView event(UUID id);
    /**
     * 分页查询当前租户的故障周期，校验时间范围、业务状态和排序，并返回签名续查游标。
     */
    Page<AlertDtos.IncidentView> incidents(IncidentFilter filter, String sort, int size, String token);
    /**
     * 统计当前租户的活动故障数量，供概览展示，避免把其他租户数据计入结果。
     */
    java.util.Map<String, Long> incidentSummary();
    /**
     * 读取当前租户单个故障周期的详情，包含状态、事件计数、时间及资源版本。
     */
    AlertDtos.IncidentView incident(UUID id);
    /**
     * 确认故障对当前租户可见后读取工单关联进度；仅由恢复消息建立的周期返回明确的 NOT_REQUIRED。
     */
    AlertDtos.IncidentTicketLinkView link(UUID id);
    /**
     * 查询页的不可变结果，组合当前页记录、下一页游标及规范化排序，不额外声明全量总数。
     */
    record Page<T>(List<T> items, String next, String sort) {}
}
