package com.flowdesk.workflow.service.recovery.impl;

import com.flowdesk.workflow.start.*;
import com.flowdesk.workflow.model.recovery.WorkflowRecoveryModels.*;
import com.flowdesk.workflow.service.recovery.*;
import com.flowdesk.workflow.dto.*;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.flowdesk.workflow.model.recovery.WorkflowRecoveryModels;
import com.flowdesk.workflow.dto.WorkflowRecoveryView;
import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.PermissionPolicy;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.workflow.service.WorkflowCursorTokenService;

import java.util.Set;

/**
 * 查询工作流所属恢复记录（API-058/059），响应只投影恢复状态，不暴露原始消息字节。
 */
public final class WorkflowRecoveryReadServiceImpl implements WorkflowRecoveryReadService {
    private static final Set<String> DIRECTIONS = Set.of("INBOX", "OUTBOX");
    private static final Set<String> STATES = Set.of("OPEN", "REPLAYING", "RESOLVED");
    private static final Set<String> SORTS = Set.of(
            "-recoveryRequiredAt", "recoveryRequiredAt", "-deadLetteredAt");
    private final WorkflowRecoveryMapper mapper;
    private final WorkflowCursorTokenService cursors;

    /**
     * 注入恢复查询映射和签名游标，按恢复路由、租户范围和分页条件提供只读视图。
     */
    public WorkflowRecoveryReadServiceImpl(WorkflowRecoveryMapper mapper,
                                WorkflowCursorTokenService cursors) {
        this.mapper = mapper; this.cursors = cursors;
    }

    /**
     * 按声明的筛选和分页条件读取当前租户可见记录，返回本页数据及下一页边界。
     */
    public Page list(String direction, String state, Boolean deadLettered, String error,
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
        List<WorkflowRecoveryModels.RecoveryFact> rows = mapper.findRecoveries(
                uuid(tenantId), direction, state, deadLettered, error, from, to, ascending,
                deadLetterSort, cursorTime, cursorId == null ? null : uuid(cursorId), pageSize + 1);
        boolean more = rows.size() > pageSize;
        if (more) rows = rows.subList(0, pageSize);
        String next = null;
        if (more && !rows.isEmpty()) {
            var last = rows.get(rows.size() - 1);
            Instant time = deadLetterSort ? last.deadLetteredAt() : last.recoveryRequiredAt();
            next = cursors.encode(tenantId, scope, pageSize, normalizedSort,
                    time.toString(), last.recoveryId());
        }
        return new Page(rows.stream().map(WorkflowRecoveryViews::toView).toList(),
                next, normalizedSort);
    }

    /**
     * 读取当前租户内指定记录，不存在或不可见时返回统一资源错误。
     */
    public WorkflowRecoveryView get(UUID recoveryId) {
        PermissionPolicy.requireCurrentPrincipal(PermissionCode.RECOVERY_READ_TENANT);
        return mapper.findRecovery(uuid(TenantContextHolder.current().tenantId()), uuid(recoveryId), false).stream().findFirst()
                .map(WorkflowRecoveryViews::toView)
                .orElseThrow(WorkflowRecoveryReadServiceImpl::routeMismatch);
    }

    /**
     * 构造恢复查询参数错误，保证不合法筛选不进入数据库查询。
     */
    private static void invalid(String field) {
        throw ApiException.badRequest("INVALID_QUERY_PARAMETER", field + " query 参数无效。");
    }
    /**
     * 检查查询参数白名单和单值约束，再读取可选值供条件组装。
     */
    private static String value(Object value) { return value == null ? "null" : value.toString(); }
    /**
     * 构造统一的恢复路由不可见错误，避免暴露不属于当前入口的事实。
     */
    private static ApiException routeMismatch() {
        return ApiException.notFound("RECOVERY_ROUTE_MISMATCH", "恢复记录不存在或不属于 Workflow 路由。");
    }
    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}
