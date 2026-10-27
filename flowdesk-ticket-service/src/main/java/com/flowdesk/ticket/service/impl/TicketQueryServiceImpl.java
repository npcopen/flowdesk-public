package com.flowdesk.ticket.service.impl;

import com.flowdesk.ticket.sla.SlaClockService;
import com.flowdesk.ticket.sla.SlaPersistenceMapper;
import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.timestamp;
import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.requireTransaction;
import static com.flowdesk.ticket.persistence.SlaPersistenceSupport.requireTenant;
import java.util.Optional;
import java.util.Collection;
import org.springframework.dao.DuplicateKeyException;
import com.flowdesk.ticket.model.sla.*;
import com.flowdesk.ticket.mapper.*;
import com.flowdesk.ticket.model.*;
import com.flowdesk.ticket.persistence.Uuids;

import com.flowdesk.ticket.service.*;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.ticket.dto.TicketDtos;

import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 工单列表、评论、时间线及异步状态的只读用例，查询始终受当前用户可见范围限制。 */
@Service
public class TicketQueryServiceImpl implements TicketQueryService {
    private static final Set<String> TICKET_STATUSES = Set.of(
            "AWAITING_APPROVAL", "APPROVAL_REJECTED", "PENDING_ASSIGNMENT", "ASSIGNED",
            "IN_PROGRESS", "RESOLVED", "CLOSED");
    private static final Set<String> TICKET_SORTS = Set.of(
            "-updatedAt", "updatedAt", "-createdAt", "createdAt", "ticketNo");
    private static final Set<String> COMMENT_SORTS = Set.of("createdAt", "-createdAt");
    private static final Set<String> TIMELINE_SORTS = Set.of("occurredAt", "-occurredAt");
    private static final Set<String> TIMELINE_EVENTS = Set.of(
            "CREATED", "APPROVAL_REQUESTED", "APPROVAL_PASSED", "APPROVAL_REJECTED",
            "ASSIGNED", "ACCEPTED", "TRANSFERRED", "COMMENT_ADDED", "RESOLVED", "CLOSED",
            "REOPENED", "INCIDENT_RECOVERED", "SLA_REMINDER_TRIGGERED",
            "SLA_BREACH_TRIGGERED", "SLA_SATISFIED_ON_TIME", "SLA_SATISFIED_LATE");

    private final TicketCommentMapper ticketCommentMapper;
    private final TicketMapper ticketMapper;
    private final TicketTimelineMapper ticketTimelineMapper;
    private final TicketWorkflowLinkMapper ticketWorkflowLinkMapper;
    private final SlaPersistenceMapper slaRepository;
    private final TicketCursorTokenService cursorTokenService;

    /**
     * 构造工单组件并保存调用依赖。
     */
    public TicketQueryServiceImpl(TicketCommentMapper ticketCommentMapper, TicketMapper ticketMapper, TicketTimelineMapper ticketTimelineMapper, TicketWorkflowLinkMapper ticketWorkflowLinkMapper, SlaPersistenceMapper slaRepository,
                              TicketCursorTokenService cursorTokenService) {
        this.ticketCommentMapper = ticketCommentMapper;
        this.ticketMapper = ticketMapper;
        this.ticketTimelineMapper = ticketTimelineMapper;
        this.ticketWorkflowLinkMapper = ticketWorkflowLinkMapper;
        this.slaRepository = slaRepository;
        this.cursorTokenService = cursorTokenService;
    }

    /** 先将用户权限转换为数据库查询范围，再将筛选条件绑定到签名游标，防止翻页扩大可见范围。 */
    public PageResult<TicketDtos.TicketResponse> list(String status, String originType,
                                                      UUID catalogItemId, Instant createdFrom,
                                                      Instant createdTo, String scope,
                                                      int pageSize, String pageToken, String sort) {
        TenantContext context = TicketSupport.context();
        RoleScope roleScope = resolveRoleScope();
        String requestedScope = normalizeListScope(roleScope, scope);
        TicketScope dbScope = switch (requestedScope) {
            case "mine" -> TicketScope.REQUESTER;
            case "assigned" -> TicketScope.ASSIGNEE;
            case "visible" -> TicketScope.VISIBLE;
            default -> TicketScope.TENANT;
        };
        if (status != null) {
            TicketSupport.requireEnumValue(status, TICKET_STATUSES,
                    "INVALID_QUERY_PARAMETER", "status 不是合法 Ticket 状态。");
        }
        if (originType != null) {
            TicketSupport.requireEnumValue(originType, Set.of("MANUAL", "INCIDENT"),
                    "INVALID_QUERY_PARAMETER", "originType 只接受 MANUAL/INCIDENT。");
        }
        String normalizedSort = normalizeSort(sort, TICKET_SORTS, "-updatedAt");
        boolean ascending = !normalizedSort.startsWith("-");
        String sortColumn = ascending ? normalizedSort : normalizedSort.substring(1);
        String cursorScope = "tickets|" + roleScope.name() + "|" + requestedScope + "|" + status
                + "|" + originType + "|" + catalogItemId + "|" + createdFrom + "|" + createdTo;
        TicketCursorTokenService.Cursor cursor = cursorTokenService.decode(
                pageToken, context.tenantId(), cursorScope, pageSize, normalizedSort);
        Object cursorValue = decodeCursorValue(cursor, sortColumn);
        List<TicketRow> rows = ticketMapper.findTickets(context.tenantId(), dbScope,
                TicketSupport.actorId(), status, originType, catalogItemId, createdFrom, createdTo,
                sortColumn, ascending, cursorValue, cursor == null ? null : cursor.lastId(), pageSize);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<TicketDtos.TicketResponse> items = rows.stream().map(TicketResponseMapper::toResponse).toList();
        String nextToken = null;
        if (hasMore && !items.isEmpty()) {
            TicketRow last = rows.get(rows.size() - 1);
            nextToken = cursorTokenService.encode(context.tenantId(), cursorScope, pageSize,
                    normalizedSort, cursorString(last, sortColumn), last.ticketId());
        }
        return new PageResult<>(items, nextToken, normalizedSort);
    }

    /**
     * 统计当前用户权限范围内的待处理工单数量，用于工作台摘要。
     */
    public Map<String, Long> summary() {
        TicketScope scope = switch (resolveRoleScope()) {
            case REQUESTER -> TicketScope.REQUESTER;
            case AGENT -> TicketScope.ASSIGNEE;
            case MIXED -> TicketScope.VISIBLE;
            case ADMIN -> TicketScope.TENANT;
        };
        return Map.of("pendingCount", ticketMapper.countPendingTickets(
                TicketSupport.context().tenantId(), scope, TicketSupport.actorId()));
    }

    /**
     * 根据 SLA 时钟查找所属工单，并再次校验该工单对当前用户是否可见。
     */
    public Map<String, UUID> ticketForSlaClock(UUID clockId) {
        var clock = findSlaClockById(TicketSupport.context().tenantId(), clockId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "相关工单不可见。"));
        findVisibleTicket(clock.ticketId());
        return Map.of("ticketId", clock.ticketId());
    }

    /**
     * 返回指定工单的对外详情；资源不存在或不在可见范围内时由实现层拒绝访问。
     */
    public TicketDtos.TicketResponse get(UUID ticketId) {
        TicketRow row = findVisibleTicket(ticketId);
        return TicketResponseMapper.toResponse(row);
    }

    /**
     * 校验工单可见性后，按创建时间和游标分页读取评论，避免评论接口泄露其他工单内容。
     */
    public PageResult<TicketDtos.TicketCommentResponse> comments(UUID ticketId, int pageSize,
                                                                 String pageToken, String sort) {
        TenantContext context = TicketSupport.context();
        findVisibleTicket(ticketId);
        String normalizedSort = normalizeSort(sort, COMMENT_SORTS, "-createdAt");
        boolean ascending = !normalizedSort.startsWith("-");
        String cursorScope = "comments|" + ticketId;
        TicketCursorTokenService.Cursor cursor = cursorTokenService.decode(
                pageToken, context.tenantId(), cursorScope, pageSize, normalizedSort);
        Object cursorValue = cursor == null ? null : Timestamp.from(Instant.parse(cursor.lastValue()));
        List<CommentRow> rows = ticketCommentMapper.findComments(context.tenantId(), ticketId,
                ascending, cursorValue, cursor == null ? null : cursor.lastId(), pageSize);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<TicketDtos.TicketCommentResponse> items = rows.stream().map(TicketResponseMapper::toResponse).toList();
        String nextToken = null;
        if (hasMore && !items.isEmpty()) {
            CommentRow last = rows.get(rows.size() - 1);
            nextToken = cursorTokenService.encode(context.tenantId(), cursorScope, pageSize,
                    normalizedSort, last.createdAt().toString(), last.commentId());
        }
        return new PageResult<>(items, nextToken, normalizedSort);
    }

    /**
     * 按事件类型和发生时间筛选工单时间线，并以稳定排序的游标返回后续记录。
     */
    public PageResult<TicketDtos.TimelineEntryResponse> timeline(UUID ticketId, String eventType,
                                                                 Instant occurredFrom, Instant occurredTo,
                                                                 int pageSize, String pageToken,
                                                                 String sort) {
        TenantContext context = TicketSupport.context();
        findVisibleTicket(ticketId);
        if (eventType != null) {
            TicketSupport.requireEnumValue(eventType, TIMELINE_EVENTS,
                    "INVALID_QUERY_PARAMETER", "eventType 不是合法时间线事件。");
        }
        String normalizedSort = normalizeSort(sort, TIMELINE_SORTS, "occurredAt");
        boolean ascending = !normalizedSort.startsWith("-");
        String cursorScope = "timeline|" + ticketId + "|" + eventType + "|" + occurredFrom + "|" + occurredTo;
        TicketCursorTokenService.Cursor cursor = cursorTokenService.decode(
                pageToken, context.tenantId(), cursorScope, pageSize, normalizedSort);
        Object cursorValue = cursor == null ? null : Timestamp.from(Instant.parse(cursor.lastValue()));
        List<TimelineRow> rows = ticketTimelineMapper.findTimelineEntries(context.tenantId(),
                ticketId, eventType, occurredFrom, occurredTo, ascending, cursorValue,
                cursor == null ? null : cursor.lastId(), pageSize);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<TicketDtos.TimelineEntryResponse> items = rows.stream().map(TicketResponseMapper::toResponse).toList();
        String nextToken = null;
        if (hasMore && !items.isEmpty()) {
            TimelineRow last = rows.get(rows.size() - 1);
            nextToken = cursorTokenService.encode(context.tenantId(), cursorScope, pageSize,
                    normalizedSort, last.occurredAt().toString(), last.timelineId());
        }
        return new PageResult<>(items, nextToken, normalizedSort);
    }

    /** 以最大 cycleNo 区分当前 SLA 周期与历史周期，历史时钟保留供追踪查看。 */
    public TicketDtos.SlaStatusResponse slaStatus(UUID ticketId) {
        TenantContext context = TicketSupport.context();
        findVisibleTicket(ticketId);
        List<com.flowdesk.ticket.model.sla.SlaClockRow> clocks = findSlaClocks(
                context.tenantId(), ticketId);
        if (clocks.isEmpty()) {
            throw ApiException.conflict("SLA_NOT_STARTED", "Ticket 尚未启动 SLA 时钟。");
        }
        Map<UUID, List<com.flowdesk.ticket.model.sla.SlaTriggerRow>> triggersByClock = new java.util.HashMap<>();
        for (com.flowdesk.ticket.model.sla.SlaTriggerRow trigger : findSlaTriggers(context.tenantId(),
                clocks.stream().map(com.flowdesk.ticket.model.sla.SlaClockRow::slaClockId).toList())) {
            triggersByClock.computeIfAbsent(trigger.slaClockId(), k -> new ArrayList<>()).add(trigger);
        }
        List<TicketDtos.SlaClockStatus> responses = clocks.stream()
                .map(clock -> toResponse(clock, triggersByClock.getOrDefault(clock.slaClockId(), List.of())))
                .toList();
        int currentCycle = clocks.stream().mapToInt(com.flowdesk.ticket.model.sla.SlaClockRow::cycleNo).max().orElse(0);
        return new TicketDtos.SlaStatusResponse(ticketId,
                responses.stream().filter(row -> row.cycleNo() == currentCycle).toList(),
                responses.stream().filter(row -> row.cycleNo() < currentCycle).toList());
    }

    /**
     * 读取工作流启动投递及关联结果，帮助前端区分处理中、启动成功和待恢复状态。
     */
    public TicketDtos.WorkflowStartStatusResponse workflowStartStatus(UUID ticketId) {
        TenantContext context = TicketSupport.context();
        findVisibleTicket(ticketId);
        WorkflowStartRow row = ticketWorkflowLinkMapper.findWorkflowStartLink(
                        context.tenantId(), ticketId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "工单审批启动状态不存在。"));
        return new TicketDtos.WorkflowStartStatusResponse(row.ticketId(), row.status(),
                row.workflowInstanceId(), row.requestedAt(), row.establishedAt(),
                row.recoveryRequiredAt(), row.recoveryReasonCode(), row.retryCount(),
                row.lastAttemptAt(), row.lockVersion(), row.createdAt(), row.updatedAt());
    }

    /**
     * 按排序字段类型恢复游标边界，供数据库执行后续页查询。
     */
    private Object decodeCursorValue(TicketCursorTokenService.Cursor cursor, String sortColumn) {
        if (cursor == null) {
            return null;
        }
        if ("ticketNo".equals(sortColumn)) {
            return cursor.lastValue();
        }
        return Timestamp.from(Instant.parse(cursor.lastValue()));
    }

    /**
     * 将排序字段转换为游标字符串，保留与查询排序一致的比较语义。
     */
    private String cursorString(TicketRow row, String sortColumn) {
        return switch (sortColumn) {
            case "ticketNo" -> row.ticketNo();
            case "updatedAt" -> row.updatedAt().toString();
            case "createdAt" -> row.createdAt().toString();
            default -> throw new IllegalArgumentException("Unsupported sort column: " + sortColumn);
        };
    }

    /**
     * 验证排序白名单并补充默认排序，拒绝不支持的排序表达式。
     */
    private static String normalizeSort(String sort, Set<String> allowed, String defaultValue) {
        if (sort == null || sort.isBlank()) {
            return defaultValue;
        }
        if (!allowed.contains(sort)) {
            throw ApiException.badRequest("SORT_UNSUPPORTED", "不支持的排序字段。");
        }
        return sort;
    }

    /**
     * 将内部持久化快照转换为对外响应，只暴露接口契约需要的字段。
     */
    private TicketDtos.SlaClockStatus toResponse(com.flowdesk.ticket.model.sla.SlaClockRow row,
                                                 List<com.flowdesk.ticket.model.sla.SlaTriggerRow> triggers) {
        List<TicketDtos.SlaTriggerStatus> triggerItems = triggers.stream()
                .map(trigger -> new TicketDtos.SlaTriggerStatus(trigger.stage(), trigger.dueAt(),
                        trigger.triggeredAt(), findSlaTriggerRecipientSnapshot(
                                TicketSupport.context().tenantId(), IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(
                                        "FD-SLA-TRIGGER-V1", TicketSupport.context().tenantId(), row.slaClockId(), trigger.stage())))
                                .isEmpty() ? "PENDING_DELIVERY" : "QUEUED"))
                .toList();
        return new TicketDtos.SlaClockStatus(row.slaClockId(), row.slaPolicyId(), row.clockType(),
                row.status(), row.startedAt(), row.deadlineAt(), row.reminderDueAt(),
                row.policyDurationSeconds(), row.policyReminderBeforeSeconds(), row.milestoneAt(),
                row.breachedAt(), row.satisfiedAt(), triggerItems, row.lockVersion(), row.cycleNo());
    }

    /** 不可见与不存在均返回 404，避免通过资源标识探测其他用户的工单。 */
    private TicketRow findVisibleTicket(UUID ticketId) {
        TenantContext context = TicketSupport.context();
        TicketRow row = ticketMapper.findTicket(context.tenantId(), ticketId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "工单不存在。"));
        if (!canView(context, row)) {
            throw ApiException.notFound("RESOURCE_NOT_FOUND", "工单不存在。");
        }
        return row;
    }

    /**
     * 根据申请人、处理人和管理权限判断当前用户是否可见该工单。
     */
    private boolean canView(TenantContext context, TicketRow row) {
        if (TicketSupport.hasAny(PermissionCode.TICKET_READ_TENANT)) {
            return true;
        }
        if (TicketSupport.hasAny(PermissionCode.TICKET_READ_OWN)
                && TicketSupport.actorId().equals(row.requesterUserId())) {
            return true;
        }
        return TicketSupport.hasAny(PermissionCode.TICKET_READ_ASSIGNED)
                && TicketSupport.actorId().equals(row.assigneeUserId());
    }

    /**
     * 根据权限组合确定申请人、处理人、混合或管理员查询范围。
     */
    private RoleScope resolveRoleScope() {
        if (TicketSupport.hasAny(PermissionCode.TICKET_READ_TENANT)) {
            return RoleScope.ADMIN;
        }
        if (TicketSupport.hasAny(PermissionCode.TICKET_READ_ASSIGNED) && TicketSupport.hasAny(PermissionCode.TICKET_READ_OWN)) return RoleScope.MIXED;
        if (TicketSupport.hasAny(PermissionCode.TICKET_READ_ASSIGNED)) {
            return RoleScope.AGENT;
        }
        if (TicketSupport.hasAny(PermissionCode.TICKET_READ_OWN)) {
            return RoleScope.REQUESTER;
        }
        throw ApiException.forbidden("ACCESS_DENIED", "当前角色或权限不足。");
    }

    /** 用户只能选择当前角色允许的查询范围，显式传入 scope 也不能扩大权限。 */
    private String normalizeListScope(RoleScope roleScope, String scope) {
        if (scope == null) {
            return roleScope.defaultScope();
        }
        if (roleScope == RoleScope.ADMIN && Set.of("tenant", "mine", "assigned").contains(scope)) return scope;
        if (roleScope == RoleScope.MIXED && Set.of("visible", "mine", "assigned").contains(scope)) return scope;
        String expected = switch (roleScope) {
            case MIXED -> "visible";
            case REQUESTER -> "mine";
            case AGENT -> "assigned";
            case ADMIN -> "tenant";
        };
        if (!expected.equals(scope)) {
            throw ApiException.forbidden("TICKET_SCOPE_FORBIDDEN", "不能扩大当前角色的 Ticket 列表范围。");
        }
        return scope;
    }

    /**
     * 用户工单查询角色组合，确定默认查询范围与可见性规则。
     */
    private enum RoleScope {
        REQUESTER("mine"), AGENT("assigned"), MIXED("visible"), ADMIN("tenant");

        private final String defaultScope;

        /**
         * 构造工单组件并保存调用依赖。
         */
        RoleScope(String defaultScope) {
            this.defaultScope = defaultScope;
        }

        /**
         * 返回当前角色组合默认的工单列表范围。
         */
        String defaultScope() {
            return defaultScope;
        }
    }

    /**
     * 按当前租户和标识读取 SLA 时钟，无匹配时返回空结果。
     */
    private Optional<SlaClockRow> findSlaClockById(UUID tenantId, UUID slaClockId) {
        requireTenant(tenantId);
        return slaRepository.findSlaClockByIdSelect(Uuids.toBytes(tenantId), Uuids.toBytes(slaClockId))
                .stream().findFirst();
    }

    /**
     * 读取工单关联的 SLA 时钟集合，供状态汇总和阶段处理。
     * 传入租户必须与当前上下文一致。
     */
    private List<SlaClockRow> findSlaClocks(UUID tenantId, UUID ticketId) {
        requireTenant(tenantId);
        return slaRepository.findSlaClocksSelect(Uuids.toBytes(tenantId), Uuids.toBytes(ticketId));
    }

    /**
     * 读取工单时钟对应的触发记录，供 SLA 详情展示。
     * 传入租户必须与当前上下文一致。
     */
    private List<SlaTriggerRow> findSlaTriggers(UUID tenantId, Collection<UUID> slaClockIds) {
        requireTenant(tenantId);
        if (slaClockIds.isEmpty()) return List.of();
        return slaRepository.findSlaTriggersSelect(Uuids.toBytes(tenantId), slaClockIds);
    }

    /**
     * 读取首次触发确定的收件人，避免重试受后续配置变化影响。
     * 传入租户必须与当前上下文一致。
     */
    private List<UUID> findSlaTriggerRecipientSnapshot(UUID tenantId, UUID triggerId) {
        requireTenant(tenantId);
        // 收件人表是不可变子集合，运行账号仅有 SELECT/INSERT 权限。
        // 调用方已通过父工单与时钟锁串行化写入，子表再加 FOR UPDATE 既多余，也违反真实 MySQL 的最小权限约束。
        return slaRepository.findSlaTriggerRecipientSnapshotSelect(Uuids.toBytes(tenantId), Uuids.toBytes(triggerId));
    }
}
