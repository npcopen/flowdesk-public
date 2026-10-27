package com.flowdesk.alert.service.impl;
import com.flowdesk.alert.service.*;
import com.flowdesk.alert.recovery.*;
import com.flowdesk.alert.mapper.AlertRecoveryMapper;
import com.flowdesk.alert.model.*;
import com.flowdesk.common.authz.*;
import com.flowdesk.common.tenant.*;
import com.flowdesk.common.web.ApiException;
import java.time.Instant;
import java.util.*;
/**
 * 校验恢复查询条件并从当前租户读取恢复记录；将数据库投影转换为视图，用绑定筛选条件的游标续查。
 */
public final class AlertRecoveryQueryServiceImpl implements AlertRecoveryQueryService {
    private static final Set<String> DIRECTIONS=Set.of("INBOX","OUTBOX"), STATES=Set.of("OPEN","REPLAYING","RESOLVED"), SORTS=Set.of("-recoveryRequiredAt","recoveryRequiredAt","-deadLetteredAt");
    private final AlertRecoveryMapper mapper;
    private final AlertCursorTokenService cursors;
    /**
     * 注入恢复记录查询 Mapper 和签名游标服务，供当前租户的恢复列表与详情查询使用。
     */
    public AlertRecoveryQueryServiceImpl(AlertRecoveryMapper mapper, AlertCursorTokenService cursors) { this.mapper=mapper; this.cursors=cursors; }
    /**
     * 校验恢复筛选值、时间范围和排序；查询当前租户的数据，多取一条判断下一页并签发绑定当前条件的游标。
     */
    public Page list(
            String direction,
            String state,
            Boolean dead,
            String error,
            Instant from,
            Instant to,
            int size,
            String token,
            String sort) {
        PermissionPolicy.requireCurrentPrincipal(PermissionCode.RECOVERY_READ_TENANT);
        if (direction != null && !DIRECTIONS.contains(direction)) invalid("direction");
        if (state != null && !STATES.contains(state)) invalid("state");
        if (error != null && (error.isBlank() || error.length() > 96)) invalid("lastErrorCode");
        if (from != null && to != null && from.isAfter(to)) invalid("time range");
        String normalized = sort == null ? "-recoveryRequiredAt" : sort;
        if (!SORTS.contains(normalized))
            throw ApiException.badRequest("SORT_UNSUPPORTED", "恢复记录排序方式不受支持。");
        UUID tenant = TenantContextHolder.current().tenantId();
        String scope =
                String.join(
                        "|",
                        "alert-recoveries",
                        value(direction),
                        value(state),
                        value(dead),
                        value(error),
                        value(from),
                        value(to));
        var cursor = cursors.decode(token, tenant, scope, size, normalized);
        Instant cursorTime = cursor == null ? null : Instant.parse(cursor.value());
        boolean asc = "recoveryRequiredAt".equals(normalized),
                deadSort = "-deadLetteredAt".equals(normalized);
        // 多取一条只用于判断是否还有下一页；时间相同时由记录 ID 保证翻页顺序稳定。
        List<AlertRecoveryRow> facts =
                mapper.listRecoveries(
                        tenant,
                        direction,
                        state,
                        dead,
                        error,
                        from,
                        to,
                        asc,
                        deadSort,
                        cursorTime,
                        cursor == null ? null : cursor.id(),
                        size + 1);
        boolean more = facts.size() > size;
        if (more) facts = facts.subList(0, size);
        String next = null;
        if (more && !facts.isEmpty()) {
            var last = facts.get(facts.size() - 1);
            next =
                    cursors.encode(
                            tenant,
                            scope,
                            size,
                            normalized,
                            new AlertCursorTokenService.InstantCursor(
                                    (deadSort ? last.dead() : last.required()).toString(),
                                    last.id()));
        }
        return new Page(
                facts.stream().map(AlertRecoveryResponseMapper::view).toList(), next, normalized);
    }
    /**
     * 读取当前租户中属于 Alert 路由的恢复记录，未命中时返回统一路由不匹配错误。
     */
    public AlertRecoveryView get(UUID id) {
        PermissionPolicy.requireCurrentPrincipal(PermissionCode.RECOVERY_READ_TENANT);
        return mapper.findRecovery(TenantContextHolder.current().tenantId(), id, false).stream().findFirst()
                .map(AlertRecoveryResponseMapper::view)
                .orElseThrow(() -> ApiException.notFound("RECOVERY_ROUTE_MISMATCH", "恢复记录不存在或不属于该路由。"));
    }
    /**
     * 以 INVALID_QUERY_PARAMETER 拒绝非法恢复查询字段，并指出触发校验的参数名。
     */
    private static void invalid(String f) {
        throw ApiException.badRequest("INVALID_QUERY_PARAMETER", f + " query 参数无效。");
    }
    /**
     * 将可选筛选值稳定转换为游标作用域文本，null 使用明确占位，保证不同筛选组合不会混用游标。
     */
    private static String value(Object v) {
        return v == null ? "null" : v.toString();
    }
}
