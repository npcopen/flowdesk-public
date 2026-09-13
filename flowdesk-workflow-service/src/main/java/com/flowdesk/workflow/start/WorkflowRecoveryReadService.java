package com.flowdesk.workflow.start;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.PermissionPolicy;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.workflow.service.WorkflowCursorTokenService;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** API-058/059 Workflow-owned recovery projection; never exposes source bytes. */
final class WorkflowRecoveryReadService {
    private static final Set<String> DIRECTIONS = Set.of("INBOX", "OUTBOX");
    private static final Set<String> STATES = Set.of("OPEN", "REPLAYING", "RESOLVED");
    private static final Set<String> SORTS = Set.of(
            "-recoveryRequiredAt", "recoveryRequiredAt", "-deadLetteredAt");
    private final WorkflowRecoveryRepository repository;
    private final WorkflowCursorTokenService cursors;

    WorkflowRecoveryReadService(WorkflowRecoveryRepository repository,
                                WorkflowCursorTokenService cursors) {
        this.repository = repository; this.cursors = cursors;
    }

    Page list(String direction, String state, Boolean deadLettered, String error,
              Instant from, Instant to, int pageSize, String pageToken, String sort) {
        PermissionPolicy.requireCurrentPrincipal(PermissionCode.RECOVERY_READ_TENANT);
        if (direction != null && !DIRECTIONS.contains(direction)) invalid("direction");
        if (state != null && !STATES.contains(state)) invalid("state");
        if (error != null && (error.isBlank() || error.length() > 96)) invalid("lastErrorCode");
        if (from != null && to != null && from.isAfter(to)) invalid("recoveryRequiredFrom/To");
        String normalizedSort = sort == null ? "-recoveryRequiredAt" : sort;
        if (!SORTS.contains(normalizedSort)) {
            throw ApiException.badRequest("SORT_UNSUPPORTED", "恢复记录排序方式不受支持。");
        }
        UUID tenantId = TenantContextHolder.current().tenantId();
        String scope = String.join("|", "workflow-recoveries", value(direction), value(state),
                value(deadLettered), value(error), value(from), value(to));
        WorkflowCursorTokenService.Cursor cursor = cursors.decode(
                pageToken, tenantId, scope, pageSize, normalizedSort);
        Instant cursorTime = cursor == null ? null : Instant.parse(cursor.lastValue());
        UUID cursorId = cursor == null ? null : cursor.lastId();
        boolean ascending = "recoveryRequiredAt".equals(normalizedSort);
        boolean deadLetterSort = "-deadLetteredAt".equals(normalizedSort);
        List<WorkflowRecoveryRepository.RecoveryFact> rows = repository.findRecoveries(
                tenantId, direction, state, deadLettered, error, from, to, ascending,
                deadLetterSort, cursorTime, cursorId, pageSize + 1);
        boolean more = rows.size() > pageSize;
        if (more) rows = rows.subList(0, pageSize);
        String next = null;
        if (more && !rows.isEmpty()) {
            var last = rows.get(rows.size() - 1);
            Instant time = deadLetterSort ? last.deadLetteredAt() : last.recoveryRequiredAt();
            next = cursors.encode(tenantId, scope, pageSize, normalizedSort,
                    time.toString(), last.recoveryId());
        }
        return new Page(rows.stream().map(WorkflowRecoveryRepository::toView).toList(),
                next, normalizedSort);
    }

    WorkflowRecoveryView get(UUID recoveryId) {
        PermissionPolicy.requireCurrentPrincipal(PermissionCode.RECOVERY_READ_TENANT);
        return repository.findRecovery(TenantContextHolder.current().tenantId(), recoveryId, false)
                .map(WorkflowRecoveryRepository::toView)
                .orElseThrow(WorkflowRecoveryReadService::routeMismatch);
    }

    private static void invalid(String field) {
        throw ApiException.badRequest("INVALID_QUERY_PARAMETER", field + " query 参数无效。");
    }
    private static String value(Object value) { return value == null ? "null" : value.toString(); }
    private static ApiException routeMismatch() {
        return ApiException.notFound("RECOVERY_ROUTE_MISMATCH", "恢复记录不存在或不属于 Workflow 路由。");
    }
    record Page(List<WorkflowRecoveryView> items, String nextPageToken, String sort) { }
}
