package com.flowdesk.ticket.sla.impl;

import com.flowdesk.ticket.sla.*;
import com.flowdesk.ticket.mapper.SlaPolicyMapper;
import com.flowdesk.ticket.model.SlaPolicyRow;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.ticket.dto.TicketDtos;
import com.flowdesk.ticket.service.PageResult;
import com.flowdesk.ticket.service.TicketCursorTokenService;
import com.flowdesk.ticket.service.TicketSupport;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/** API-020 SLA 策略只读用例，为目录配置与运行时展示提供策略事实。 */
@Service
public class SlaPolicyServiceImpl implements SlaPolicyService {

    private static final Set<String> SORTS = Set.of("policyName", "-policyName", "policyCode", "-policyCode");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");

    private final SlaPolicyMapper repository;
    private final TicketCursorTokenService cursorTokenService;

    /**
     * 构造SLA组件并保存调用依赖。
     */
    public SlaPolicyServiceImpl(SlaPolicyMapper repository, TicketCursorTokenService cursorTokenService) {
        this.repository = repository;
        this.cursorTokenService = cursorTokenService;
    }

    /**
     * 按筛选条件与稳定游标分页查询SLA数据，返回结果及后续页边界。
     */
    public PageResult<TicketDtos.SlaPolicySummaryResponse> list(String status, int pageSize,
                                                                String pageToken, String sort) {
        TenantContext context = TicketSupport.context();
        TicketSupport.require(PermissionCode.CATALOG_READ_TENANT);
        if (status != null) {
            TicketSupport.requireEnumValue(status, STATUSES,
                    "INVALID_QUERY_PARAMETER", "status 只接受 ACTIVE/INACTIVE。");
        }
        String normalizedSort = normalizeSort(sort);
        boolean ascending = !normalizedSort.startsWith("-");
        String sortColumn = ascending ? normalizedSort : normalizedSort.substring(1);
        String scope = "sla|" + status;
        TicketCursorTokenService.Cursor cursor = cursorTokenService.decode(
                pageToken, context.tenantId(), scope, pageSize, normalizedSort);
        List<SlaPolicyRow> rows = repository.findSlaPolicies(
                context.tenantId(), status, sortColumn, ascending,
                cursor == null ? null : cursor.lastValue(),
                cursor == null ? null : cursor.lastId(), pageSize);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<TicketDtos.SlaPolicySummaryResponse> items = rows.stream().map(this::toResponse).toList();
        String nextToken = null;
        if (hasMore && !items.isEmpty()) {
            SlaPolicyRow last = rows.get(rows.size() - 1);
            nextToken = cursorTokenService.encode(context.tenantId(), scope, pageSize, normalizedSort,
                    sortValue(last, sortColumn), last.slaPolicyId());
        }
        return new PageResult<>(items, nextToken, normalizedSort);
    }

    /**
     * 将内部持久化快照转换为对外响应，只暴露接口契约需要的字段。
     */
    private TicketDtos.SlaPolicySummaryResponse toResponse(SlaPolicyRow row) {
        return new TicketDtos.SlaPolicySummaryResponse(row.slaPolicyId(), row.policyCode(),
                row.policyName(), row.firstResponseSeconds(), row.resolutionSeconds(),
                row.firstResponseReminderBeforeSeconds(), row.resolutionReminderBeforeSeconds(),
                row.status(), row.lockVersion());
    }

    /**
     * 验证排序白名单并补充默认排序，拒绝不支持的排序表达式。
     */
    private static String normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "policyName";
        }
        if (!SORTS.contains(sort)) {
            throw ApiException.badRequest("SORT_UNSUPPORTED", "不支持的排序字段。");
        }
        return sort;
    }

    /**
     * 提取当前排序字段值，供构造下一页的稳定游标。
     */
    private static String sortValue(SlaPolicyRow row, String column) {
        return "policyCode".equals(column) ? row.policyCode() : row.policyName();
    }
}
