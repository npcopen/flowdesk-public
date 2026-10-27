package com.flowdesk.alert.service.impl;
import com.flowdesk.alert.model.IncidentCycleRow;
import com.flowdesk.alert.model.EventRow;

import com.flowdesk.alert.service.*;
import com.flowdesk.alert.model.EventFilter;
import com.flowdesk.alert.model.IncidentFilter;

import com.flowdesk.alert.dto.AlertDtos;
import com.flowdesk.alert.mapper.*;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.web.ApiException;

import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 面向当前租户的只读查询服务，负责筛选语义、分页游标和 HTTP 视图转换。
 *
 * <p>AlertController 处理参数格式与权限，本类继续检查枚举、时间范围和支持的排序方式，
 * 再交给按能力拆分的 Mapper 查询。租户始终取自认证上下文，不由请求参数指定。
 * 这里返回查询投影，不改写告警接纳时保存的原始事实。
 */
public class AlertQueryServiceImpl implements AlertQueryService {
    private static final Set<String> EVENT_SORTS =
            Set.of("-receivedAt", "receivedAt", "-occurredAt");
    private static final Set<String> INCIDENT_SORTS =
            Set.of("-lastEventAt", "lastEventAt", "-openedAt");
    private final AlertEventMapper events;
    private final IncidentMapper incidents;
    private final AlertMessageMapper messages;
    private final AlertCursorTokenService cursors;

    /**
     * 注入事件、故障、关联消息的只读 Mapper 及游标服务，集中执行租户过滤、分页和视图映射。
     */
    public AlertQueryServiceImpl(AlertEventMapper events, IncidentMapper incidents, AlertMessageMapper messages, AlertCursorTokenService cursors) {
        this.events = events;
        this.incidents = incidents;
        this.messages = messages;
        this.cursors = cursors;
    }

    /** 按“排序时间 + 事件 ID”续查；多取一条判断是否存在下一页，避免额外统计总数。 */
    public Page<AlertDtos.AlertEventView> events(
            EventFilter filter, String sort, int size, String token) {
        if (filter.receivedFrom() != null
                && filter.receivedTo() != null
                && filter.receivedFrom().isAfter(filter.receivedTo())) invalid();
        oneOf(filter.eventType(), "FIRING", "RESOLVED");
        oneOf(filter.disposition(), "APPLIED", "LATE_OLD_GENERATION", "REVIEW_REQUIRED");
        String normalized = sort == null ? "-receivedAt" : sort;
        if (!EVENT_SORTS.contains(normalized)) unsupported();
        UUID tenant = TenantContextHolder.current().tenantId();
        String scope = "alert-events|" + filter;
        var cursor = cursors.decode(token, tenant, scope, size, normalized);
        Instant time = cursor == null ? null : Instant.parse(cursor.value());
        List<EventRow> rows =
                events.listEvents(
                        tenant,
                        filter,
                        normalized,
                        time,
                        cursor == null ? null : cursor.id(),
                        size + 1);
        boolean more = rows.size() > size;
        if (more) rows = rows.subList(0, size);
        String next = null;
        if (more && !rows.isEmpty()) {
            var last = rows.get(rows.size() - 1);
            Instant value =
                    "-occurredAt".equals(normalized) ? last.occurredAt() : last.receivedAt();
            next =
                    cursors.encode(
                            tenant,
                            scope,
                            size,
                            normalized,
                            new AlertCursorTokenService.InstantCursor(
                                    value.toString(), last.alertEventId()));
        }
        return new Page<>(rows.stream().map(this::event).toList(), next, normalized);
    }

    /**
     * 按事件标识读取当前租户的事件，未命中时返回资源不存在；随后通过视图映射补齐允许展示的关联信息。
     */
    public AlertDtos.AlertEventView event(UUID id) {
        UUID tenant = TenantContextHolder.current().tenantId();
        return events.findEvent(tenant, id)
                .map(this::event)
                .orElseThrow(AlertQueryServiceImpl::notFound);
    }

    /** 故障分页沿用事件列表的游标规则，但绑定独立的筛选作用域，不能混用游标。 */
    public Page<AlertDtos.IncidentView> incidents(
            IncidentFilter filter, String sort, int size, String token) {
        if (filter.openedFrom() != null
                && filter.openedTo() != null
                && filter.openedFrom().isAfter(filter.openedTo())) invalid();
        oneOf(filter.status(), "ACTIVE", "RECOVERED");
        oneOf(filter.ticketLinkStatus(), "REQUESTED", "ESTABLISHED", "RECOVERY_REQUIRED");
        String normalized = sort == null ? "-lastEventAt" : sort;
        if (!INCIDENT_SORTS.contains(normalized)) unsupported();
        UUID tenant = TenantContextHolder.current().tenantId();
        String scope = "incidents|" + filter;
        var cursor = cursors.decode(token, tenant, scope, size, normalized);
        Instant time = cursor == null ? null : Instant.parse(cursor.value());
        List<IncidentCycleRow> rows =
                incidents.listIncidents(
                        tenant,
                        filter,
                        normalized,
                        time,
                        cursor == null ? null : cursor.id(),
                        size + 1);
        boolean more = rows.size() > size;
        if (more) rows = rows.subList(0, size);
        String next = null;
        if (more && !rows.isEmpty()) {
            var last = rows.get(rows.size() - 1);
            Instant value = "-openedAt".equals(normalized) ? last.openedAt() : last.lastEventAt();
            next =
                    cursors.encode(
                            tenant,
                            scope,
                            size,
                            normalized,
                            new AlertCursorTokenService.InstantCursor(
                                    value.toString(), last.incidentId()));
        }
        return new Page<>(
                rows.stream().map(AlertQueryServiceImpl::incident).toList(), next, normalized);
    }

    /**
     * 统计当前租户的活动故障数量，供概览展示，避免把其他租户数据计入结果。
     */
    public java.util.Map<String, Long> incidentSummary() {
        return java.util.Map.of(
                "activeCount",
                incidents.countActiveIncidents(TenantContextHolder.current().tenantId()));
    }

    /**
     * 读取当前租户指定故障周期，未命中时返回资源不存在，并将数据库快照转换为对外视图。
     */
    public AlertDtos.IncidentView incident(UUID id) {
        UUID tenant = TenantContextHolder.current().tenantId();
        return incidents.findIncident(tenant, id, false)
                .map(AlertQueryServiceImpl::incident)
                .orElseThrow(AlertQueryServiceImpl::notFound);
    }

    /**
     * 先确认当前租户可见该故障，再查询建单进度。
     * 恢复先到的周期没有建单需求，用 NOT_REQUIRED 表达业务结果，而不是误报关联数据缺失。
     */
    public AlertDtos.IncidentTicketLinkView link(UUID id) {
        UUID tenant = TenantContextHolder.current().tenantId();
        if (incidents.findIncident(tenant, id, false).isEmpty()) throw notFound();
        // 仅由恢复消息建立的故障无需建单，返回明确的 NOT_REQUIRED 关联视图。
        if (incidents.isRecoveryOnlyIncident(tenant, id)) {
            return new AlertDtos.IncidentTicketLinkView(
                    id, "NOT_REQUIRED", null, null, null, null, null, 0, null, 0, null, null);
        }
        return messages.findLink(tenant, id, false)
                .map(
                        row ->
                                new AlertDtos.IncidentTicketLinkView(
                                        row.incidentId(),
                                        row.status(),
                                        row.ticketId(),
                                        row.requestedAt(),
                                        row.establishedAt(),
                                        row.recoveryRequiredAt(),
                                        row.recoveryReasonCode(),
                                        row.retryCount(),
                                        row.lastAttemptAt(),
                                        row.lockVersion(),
                                        row.createdAt(),
                                        row.updatedAt()))
                .orElseThrow(AlertQueryServiceImpl::notFound);
    }

    /**
     * 将事件快照转换为响应；历史事件尚未关联故障时，仅在指纹与周期恰好匹配一条记录时补充响应关联，不改写原始事件。
     */
    private AlertDtos.AlertEventView event(EventRow row) {
        UUID incidentId = row.incidentId();
        String disposition = row.disposition();
        // 兼容先持久化、后补建周期的告警；唯一匹配时只更新响应投影，保留原事件与审计事实。
        if (incidentId == null) {
            var cycles =
                    incidents.findByCyclePreTrust(
                            TenantContextHolder.current().tenantId(),
                            row.fingerprint(),
                            row.cycleRef());
            if (cycles.size() == 1) {
                incidentId = cycles.get(0).incidentId();
                disposition = "APPLIED";
            }
        }
        return new AlertDtos.AlertEventView(
                row.alertEventId(),
                new AlertDtos.IntegrationRef(
                        row.integrationId(), row.integrationCode(), row.integrationName()),
                row.externalEventId(),
                row.eventType(),
                row.fingerprint(),
                row.cycleRef(),
                row.occurredAt(),
                row.receivedAt(),
                disposition,
                incidentId,
                HexFormat.of().formatHex(row.rawBodySha256()),
                row.summary(),
                row.correlationId());
    }

    /**
     * 将故障周期快照逐字段映射为响应，保留状态、事件计数、开启和恢复时间及乐观锁版本。
     */
    private static AlertDtos.IncidentView incident(IncidentCycleRow row) {
        return new AlertDtos.IncidentView(
                row.incidentId(),
                row.fingerprint(),
                row.generationNo(),
                row.status(),
                row.eventCount(),
                row.openedAt(),
                row.lastEventAt(),
                row.recoveredAt(),
                row.summary(),
                row.lockVersion(),
                row.createdAt(),
                row.updatedAt());
    }

    /**
     * 校验可选筛选值是否在允许枚举集合内，未提供时保留不筛选语义，非法值统一拒绝。
     */
    private static void oneOf(String value, String... allowed) {
        if (value == null) return;
        if (!java.util.Arrays.asList(allowed).contains(value)) invalid();
    }

    /**
     * 抛出不支持排序的契约错误，阻止任意排序字段继续进入查询层。
     */
    private static void unsupported() {
        throw ApiException.badRequest("SORT_UNSUPPORTED", "排序方式不受支持。");
    }

    /**
     * 构造当前校验路径使用的统一参数或游标错误，保证错误响应遵循对外契约。
     */
    private static void invalid() {
        throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "query 参数无效。");
    }

    /**
     * 构造资源不存在的统一响应，当前租户无匹配数据时不泄露其他租户资源。
     */
    private static ApiException notFound() {
        return ApiException.notFound("RESOURCE_NOT_FOUND", "Alert 资源不存在。");
    }

}
