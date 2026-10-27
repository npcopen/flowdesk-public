package com.flowdesk.notification.recovery.impl;
import com.flowdesk.notification.model.*;
import com.flowdesk.notification.recovery.*;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.notification.dto.NotificationDtos;
import com.flowdesk.notification.mapper.NotificationRecoveryMapper;
import com.flowdesk.notification.service.NotificationCursorTokenService;
import com.flowdesk.notification.service.NotificationSupport;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 查询租户恢复列表及详情，只投影恢复状态，不读取原始重放报文。 */
@Service
public class NotificationRecoveryQueryServiceImpl implements NotificationRecoveryQueryService {
    private final NotificationRecoveryMapper repository;
    private final NotificationCursorTokenService cursors;

    /**
     * 注入恢复专用读取映射和签名游标服务，使恢复列表使用受控数据库权限并保持翻页条件一致。
     */
    public NotificationRecoveryQueryServiceImpl(
            @Qualifier("notificationPrivilegedRecoveryMapper") NotificationRecoveryMapper repository,
            NotificationCursorTokenService cursors) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
    }

    /**
     * 按声明的筛选和分页条件读取当前租户可见记录，返回本页数据及下一页边界。
     */
    public Page<NotificationDtos.MessageRecoveryResponse> list(String direction, String state,
                                                                 Boolean deadLettered, String errorCode,
                                                                 Instant from, Instant to, String size,
                                                                 String token, String sort) {
        NotificationSupport.require(PermissionCode.RECOVERY_READ_TENANT);
        if (direction != null && !"INBOX".equals(direction)) {
            throw ApiException.badRequest("RECOVERY_ROUTE_MISMATCH", "Notification 只支持 INBOX Recovery。");
        }
        if (state != null && !java.util.Set.of("OPEN", "REPLAYING", "RESOLVED").contains(state)) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "Recovery state 无效。");
        }
        int pageSize = parseSize(size);
        String normalized = sort == null || sort.isBlank() ? "-recoveryRequiredAt" : sort;
        if (!java.util.Set.of("recoveryRequiredAt", "-recoveryRequiredAt", "-deadLetteredAt").contains(normalized)) {
            throw ApiException.badRequest("SORT_UNSUPPORTED", "排序方式不受支持。");
        }
        boolean ascending = !normalized.startsWith("-");
        String scope = "notification-recoveries|" + direction + "|" + state + "|" + deadLettered
                + "|" + errorCode + "|" + from + "|" + to;
        var cursor = cursors.decode(token, NotificationSupport.context().tenantId(), scope,
                pageSize, normalized);
        List<NotificationRecoveryRow> rows = repository.listRecoveries(NotificationSupport.context().tenantId(), direction, state, deadLettered, errorCode, from, to, ascending, cursor == null ? null : cursor.value(), cursor == null ? null : cursor.id(), Math.max(1, Math.min(101, pageSize + 1)));
        boolean more = rows.size() > pageSize;
        if (more) rows = rows.subList(0, pageSize);
        String next = null;
        if (more && !rows.isEmpty()) {
            var last = rows.get(rows.size() - 1);
            Instant cursorValue = "-deadLetteredAt".equals(normalized)
                    ? last.deadLetteredAt() : last.recoveryRequiredAt();
            if (cursorValue == null) {
                throw new IllegalStateException("Recovery sort cursor is missing");
            }
            next = cursors.encode(NotificationSupport.context().tenantId(), scope, pageSize,
                    normalized, cursorValue, last.recoveryId());
        }
        return new Page<>(rows.stream().map(NotificationRecoveryResponseMapper::response).toList(), next, normalized);
    }

    /**
     * 读取当前租户内指定记录，不存在或不可见时返回统一资源错误。
     */
    public NotificationDtos.MessageRecoveryResponse get(UUID recoveryId) {
        NotificationSupport.require(PermissionCode.RECOVERY_READ_TENANT);
        return repository.findRecovery(NotificationSupport.context().tenantId(), recoveryId, false).stream().findFirst()
                .map(NotificationRecoveryResponseMapper::response)
                .orElseThrow(() -> ApiException.notFound("RECOVERY_ROUTE_MISMATCH", "Recovery 不存在或不属于该路由。"));
    }

    /**
     * 解析恢复列表分页大小，保证数据库读取数量不超过接口限制。
     */
    private static int parseSize(String raw) {
        if (raw == null) return 20;
        try { int v = Integer.parseInt(raw); if (v >= 1 && v <= 100) return v; }
        catch (NumberFormatException ignored) { }
        throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "pageSize 必须为 1..100。");
    }

}
