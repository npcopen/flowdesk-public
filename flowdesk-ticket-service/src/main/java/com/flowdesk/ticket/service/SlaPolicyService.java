package com.flowdesk.ticket.service;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.ticket.api.TicketDtos;
import com.flowdesk.ticket.repository.TicketRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/** API-020 SLA policy reads used for catalog binding and runtime evidence. */
@Service
public class SlaPolicyService {

    private static final Set<String> SORTS = Set.of("policyName", "-policyName", "policyCode", "-policyCode");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");

    private final TicketRepository repository;
    private final TicketCursorTokenService cursorTokenService;

    public SlaPolicyService(TicketRepository repository, TicketCursorTokenService cursorTokenService) {
        this.repository = repository;
        this.cursorTokenService = cursorTokenService;
    }

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
        List<TicketRepository.SlaPolicyRow> rows = repository.findSlaPolicies(
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
            TicketRepository.SlaPolicyRow last = rows.get(rows.size() - 1);
            nextToken = cursorTokenService.encode(context.tenantId(), scope, pageSize, normalizedSort,
                    sortValue(last, sortColumn), last.slaPolicyId());
        }
        return new PageResult<>(items, nextToken, normalizedSort);
    }

    public TicketDtos.SlaPolicySummaryResponse toResponse(TicketRepository.SlaPolicyRow row) {
        return new TicketDtos.SlaPolicySummaryResponse(row.slaPolicyId(), row.policyCode(),
                row.policyName(), row.firstResponseSeconds(), row.resolutionSeconds(),
                row.firstResponseReminderBeforeSeconds(), row.resolutionReminderBeforeSeconds(),
                row.status(), row.lockVersion());
    }

    private static String normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "policyName";
        }
        if (!SORTS.contains(sort)) {
            throw ApiException.badRequest("SORT_UNSUPPORTED", "不支持的排序字段。");
        }
        return sort;
    }

    private static String sortValue(TicketRepository.SlaPolicyRow row, String column) {
        return "policyCode".equals(column) ? row.policyCode() : row.policyName();
    }
}
