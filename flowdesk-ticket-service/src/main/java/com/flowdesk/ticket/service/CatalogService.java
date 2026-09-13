package com.flowdesk.ticket.service;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.ticket.api.TicketDtos;
import com.flowdesk.ticket.repository.TicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** API-017/018/019 service catalog reads and minimal maintenance. */
@Service
public class CatalogService {

    private static final Set<String> SORTS = Set.of("itemName", "-itemName", "itemCode", "-itemCode");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");

    private final TicketRepository repository;
    private final TicketCursorTokenService cursorTokenService;

    public CatalogService(TicketRepository repository, TicketCursorTokenService cursorTokenService) {
        this.repository = repository;
        this.cursorTokenService = cursorTokenService;
    }

    public PageResult<TicketDtos.ServiceCatalogItemResponse> list(String status, String riskLevel,
                                                                  int pageSize, String pageToken,
                                                                  String sort) {
        TenantContext context = TicketSupport.context();
        boolean admin = TicketSupport.hasAny(PermissionCode.CATALOG_READ_TENANT);
        if (!admin) {
            TicketSupport.require(PermissionCode.CATALOG_READ_ACTIVE);
            if (status != null) {
                throw ApiException.forbidden("CATALOG_FILTER_SCOPE_FORBIDDEN",
                        "Requester 不能使用 status 过滤。");
            }
            status = "ACTIVE";
        }
        if (riskLevel != null) {
            TicketSupport.requireEnumValue(riskLevel, Set.of("NORMAL", "HIGH"),
                    "INVALID_QUERY_PARAMETER", "riskLevel 只接受 NORMAL/HIGH。");
        }
        if (status != null) {
            TicketSupport.requireEnumValue(status, STATUSES,
                    "INVALID_QUERY_PARAMETER", "status 只接受 ACTIVE/INACTIVE。");
        }
        String normalizedSort = normalizeSort(sort);
        boolean ascending = !normalizedSort.startsWith("-");
        String sortColumn = ascending ? normalizedSort : normalizedSort.substring(1);
        String scope = "catalog|" + (admin ? "ADMIN" : "REQUESTER") + "|" + status + "|" + riskLevel;
        TicketCursorTokenService.Cursor cursor = cursorTokenService.decode(
                pageToken, context.tenantId(), scope, pageSize, normalizedSort);
        List<TicketRepository.CatalogItemRow> rows = repository.findCatalogItems(
                context.tenantId(), status, riskLevel, sortColumn, ascending,
                cursor == null ? null : cursor.lastValue(),
                cursor == null ? null : cursor.lastId(), pageSize);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<TicketDtos.ServiceCatalogItemResponse> items = rows.stream().map(this::toResponse).toList();
        String nextToken = null;
        if (hasMore && !items.isEmpty()) {
            TicketRepository.CatalogItemRow last = rows.get(rows.size() - 1);
            nextToken = cursorTokenService.encode(context.tenantId(), scope, pageSize, normalizedSort,
                    sortValue(last, sortColumn), last.catalogItemId());
        }
        return new PageResult<>(items, nextToken, normalizedSort);
    }

    public TicketDtos.ServiceCatalogItemResponse get(UUID catalogItemId) {
        TenantContext context = TicketSupport.context();
        boolean admin = TicketSupport.hasAny(PermissionCode.CATALOG_READ_TENANT);
        if (!admin) {
            TicketSupport.require(PermissionCode.CATALOG_READ_ACTIVE);
        }
        TicketRepository.CatalogItemRow row = repository.findCatalogItem(context.tenantId(), catalogItemId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "目录项不存在。"));
        if (!admin && !"ACTIVE".equals(row.status())) {
            throw ApiException.notFound("RESOURCE_NOT_FOUND", "目录项不存在。");
        }
        return toResponse(row);
    }

    @Transactional
    public TicketDtos.ServiceCatalogItemResponse patch(UUID catalogItemId,
                                                       TicketDtos.ServiceCatalogPatchRequest request,
                                                       long expectedLockVersion) {
        TenantContext context = TicketSupport.context();
        TicketSupport.require(PermissionCode.CATALOG_UPDATE_MINIMAL);
        if (request.itemName() == null && !request.descriptionPresent()
                && request.slaPolicyId() == null && request.status() == null) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "至少提供一个可修改字段。",
                    List.of(new com.flowdesk.common.web.FieldViolation(
                            "/body", "EMPTY_PATCH", "至少提供一个可修改字段。")));
        }
        TicketRepository.CatalogItemRow existing = repository
                .findCatalogItemForUpdate(context.tenantId(), catalogItemId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "目录项不存在。"));
        if (existing.lockVersion() != expectedLockVersion) {
            throw TicketSupport.versionConflict(TicketSupport.etagHeaders(existing.lockVersion()));
        }
        if (request.slaPolicyId() != null
                && repository.findActiveSlaPolicy(context.tenantId(), request.slaPolicyId()).isEmpty()) {
            throw ApiException.unprocessable("SLA_POLICY_NOT_AVAILABLE",
                    "所选 SLA Policy 不存在或非 ACTIVE。", List.of());
        }
        Instant now = Instant.now();
        int updated = repository.updateCatalogItem(context.tenantId(), catalogItemId,
                expectedLockVersion, request.itemName(), request.description(),
                request.descriptionPresent(),
                request.slaPolicyId(), request.status() == null ? null : request.status().name(),
                TicketSupport.actorId(), now);
        if (updated != 1) {
            TicketRepository.CatalogItemRow refreshed = repository.findCatalogItem(
                            context.tenantId(), catalogItemId)
                    .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "目录项不存在。"));
            throw TicketSupport.versionConflict(TicketSupport.etagHeaders(refreshed.lockVersion()));
        }
        repository.insertAudit(context.tenantId(), UUID.randomUUID(), "CATALOG_UPDATE",
                "CATALOG_ITEM", catalogItemId, "SUCCEEDED", "USER", TicketSupport.actorId(),
                null, context.correlationId(), now);
        TicketRepository.CatalogItemRow updatedRow = repository.findCatalogItem(
                        context.tenantId(), catalogItemId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "目录项不存在。"));
        return toResponse(updatedRow);
    }

    public TicketDtos.ServiceCatalogItemResponse toResponse(TicketRepository.CatalogItemRow row) {
        TicketDtos.WorkflowRef workflowRef = row.workflowDefinitionId() == null ? null
                : new TicketDtos.WorkflowRef(row.workflowDefinitionId(), row.workflowVersionId(),
                        row.workflowVersionNo());
        return new TicketDtos.ServiceCatalogItemResponse(row.catalogItemId(), row.itemCode(),
                row.itemName(), row.description(), row.riskLevel(), row.requiresApproval(),
                workflowRef, row.slaPolicyId(), row.status(), row.lockVersion(),
                row.createdAt(), row.updatedAt());
    }

    private static String normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "itemName";
        }
        if (!SORTS.contains(sort)) {
            throw ApiException.badRequest("SORT_UNSUPPORTED", "不支持的排序字段。");
        }
        return sort;
    }

    private static String sortValue(TicketRepository.CatalogItemRow row, String column) {
        return "itemCode".equals(column) ? row.itemCode() : row.itemName();
    }
}
