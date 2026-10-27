package com.flowdesk.ticket.recovery.impl;

import com.flowdesk.ticket.recovery.*;

import java.util.Optional;
import java.util.HexFormat;
import java.sql.Timestamp;
import com.flowdesk.common.recovery.RecoveryReplayResumer;
import com.flowdesk.ticket.persistence.Uuids;
import com.flowdesk.ticket.model.recovery.*;
import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.PermissionPolicy;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.ticket.service.TicketCursorTokenService;
import com.flowdesk.ticket.service.TicketSupport;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 工单消息恢复用例：权限校验通过后，在事务内核对版本、状态及请求幂等，再受理重放执行。 */
public final class TicketRecoveryServiceImpl implements TicketRecoveryService {
    private static final Set<String> DIRECTIONS = Set.of("INBOX", "OUTBOX");
    private static final Set<String> STATES = Set.of("OPEN", "REPLAYING", "RESOLVED");
    private static final Set<String> SORTS = Set.of(
            "-recoveryRequiredAt", "recoveryRequiredAt", "-deadLetteredAt");
    private final TicketRecoveryPersistenceMapper mapper;
    private final TicketCursorTokenService cursors;
    private final TransactionTemplate transactions;
    private final TicketRecoveryReplayService worker;

    /**
     * 构造消息恢复组件并保存调用依赖。
     */
    public TicketRecoveryServiceImpl(TicketRecoveryPersistenceMapper mapper, TicketCursorTokenService cursors,
                          PlatformTransactionManager transactionManager,
                          TicketRecoveryReplayService worker) {
        this.mapper = mapper; this.cursors = cursors;
        this.transactions = new TransactionTemplate(transactionManager); this.worker = worker;
    }

    /**
     * 按筛选条件与稳定游标分页查询消息恢复数据，返回结果及后续页边界。
     */
    public Page list(String direction, String state, Boolean deadLettered, String error,
                     Instant from, Instant to, int pageSize, String pageToken, String sort) {
        PermissionPolicy.requireCurrentPrincipal(PermissionCode.RECOVERY_READ_TENANT);
        if (direction != null && !DIRECTIONS.contains(direction)) invalid("direction");
        if (state != null && !STATES.contains(state)) invalid("state");
        if (error != null && (error.isBlank() || error.length() > 96)) invalid("lastErrorCode");
        if (from != null && to != null && from.isAfter(to)) invalid("time range");
        String normalized = sort == null ? "-recoveryRequiredAt" : sort;
        if (!SORTS.contains(normalized)) throw ApiException.badRequest(
                "SORT_UNSUPPORTED", "恢复记录排序方式不受支持。");
        UUID tenant = TenantContextHolder.current().tenantId();
        String scope = String.join("|", "ticket-recoveries", value(direction), value(state),
                value(deadLettered), value(error), value(from), value(to));
        TicketCursorTokenService.Cursor cursor = cursors.decode(
                pageToken, tenant, scope, pageSize, normalized);
        Instant cursorTime = cursor == null ? null : Instant.parse(cursor.lastValue());
        boolean ascending = "recoveryRequiredAt".equals(normalized);
        boolean deadSort = "-deadLetteredAt".equals(normalized);
        List<com.flowdesk.ticket.model.recovery.Recovery> facts = listFacts(tenant, direction, state,
                deadLettered, error, from, to, ascending, deadSort, cursorTime,
                cursor == null ? null : cursor.lastId(), pageSize + 1);
        boolean more = facts.size() > pageSize;
        if (more) facts = facts.subList(0, pageSize);
        String next = null;
        if (more && !facts.isEmpty()) {
            var last = facts.get(facts.size() - 1);
            Instant time = deadSort ? last.deadLetteredAt() : last.recoveryRequiredAt();
            next = cursors.encode(tenant, scope, pageSize, normalized,
                    time.toString(), last.recoveryId());
        }
        return new Page(facts.stream().map(TicketRecoveryServiceImpl::view).toList(), next, normalized);
    }

    /**
     * 读取指定消息恢复详情，并依据当前租户与权限校验资源可见性。
     */
    public TicketRecoveryView get(UUID recoveryId) {
        PermissionPolicy.requireCurrentPrincipal(PermissionCode.RECOVERY_READ_TENANT);
        return find(TenantContextHolder.current().tenantId(), recoveryId, false)
                .map(TicketRecoveryServiceImpl::view).orElseThrow(TicketRecoveryServiceImpl::routeMismatch);
    }

    /** 在事务内受理并记录重放意图，事务提交后才向专用执行器提交 Inbox 重放任务。 */
    public ReplayResult replay(UUID recoveryId, String reason, String key, String ifMatch) {
        PermissionPolicy.requireCurrentPrincipal(PermissionCode.RECOVERY_REPLAY_TENANT);
        TenantContext context = TenantContextHolder.current();
        UUID actorId = TicketSupport.actorId();
        String acceptedReason = TicketRecoverySupport.reason(reason);
        String acceptedKey = TicketRecoverySupport.key(key);
        long expectedVersion = TicketRecoverySupport.version(ifMatch);
        Acceptance accepted = Objects.requireNonNull(transactions.execute(tx -> accept(
                context, recoveryId, actorId, acceptedReason, acceptedKey, expectedVersion)));
        if (!accepted.replayed() && accepted.work() != null) worker.submit(accepted.work());
        return new ReplayResult(accepted.view(), accepted.replayed());
    }

    /** 锁定来源与恢复记录，先复用同意图审计，再校验 OPEN 状态及版本，原子授予本次重放。 */
    private Acceptance accept(TenantContext context, UUID recoveryId, UUID actorId,
                              String reason, String key, long expectedVersion) {
        UUID auditId = TicketRecoveryIdempotency.auditId(context, recoveryId, key);
        String digest = TicketRecoveryIdempotency.requestDigest(recoveryId, reason);
        com.flowdesk.ticket.model.recovery.Recovery candidate = find(context.tenantId(), recoveryId, false).orElseThrow(TicketRecoveryServiceImpl::routeMismatch);
        if ("INBOX".equals(candidate.direction())) {
            var source = inboxSource(context.tenantId(), candidate.sourceRecordId(), true)
                    .filter(TicketRecoveryServiceImpl::activatedInbox).orElseThrow(TicketRecoveryServiceImpl::routeMismatch);
            var recovery = locked(context.tenantId(), recoveryId, "INBOX", source.inboxId());
            var prior = audit(context.tenantId(), auditId);
            if (prior.isPresent()) {
                validateAudit(prior.get(), recoveryId, actorId, digest, source.correlationId());
                return new Acceptance(view(recovery), true, null);
            }
            validateReplayable(recovery, source.status(), expectedVersion);
            Instant now = now(); UUID attempt = UUID.randomUUID();
            if (acceptInbox(context.tenantId(), recovery, actorId,
                    reason, attempt, now) != 1) replayConflict();
            insertAudit(context.tenantId(), auditId,
                    "TICKET_RECOVERY_REPLAY_REQUESTED", recoveryId, "SUCCEEDED", actorId,
                    digest, null, attempt, source.correlationId(), now);
            var accepted = find(context.tenantId(), recoveryId, false).orElseThrow();
            return new Acceptance(view(accepted), false,
                    new TicketReplayWork(context.tenantId(), recoveryId, source.inboxId(), actorId,
                            reason, attempt, source.correlationId(), accepted.nextAttemptAt()));
        }
        if ("OUTBOX".equals(candidate.direction())) {
            var source = outboxSource(context.tenantId(), candidate.sourceRecordId(), true)
                    .filter(TicketRecoveryServiceImpl::activatedOutbox).orElseThrow(TicketRecoveryServiceImpl::routeMismatch);
            var recovery = locked(context.tenantId(), recoveryId, "OUTBOX", source.outboxId());
            var prior = audit(context.tenantId(), auditId);
            if (prior.isPresent()) {
                validateAudit(prior.get(), recoveryId, actorId, digest, source.correlationId());
                return new Acceptance(view(recovery), true, null);
            }
            validateReplayable(recovery, source.status(), expectedVersion);
            if ("flowdesk.workflow.start.v1".equals(source.contractRef())
                    && !"RECOVERY_REQUIRED".equals(source.linkStatus())) throw ApiException.conflict(
                    "RECOVERY_NOT_REPLAYABLE", "WorkflowStartLink 已经收敛。");
            Instant now = now(); UUID attempt = UUID.randomUUID();
            if (acceptOutbox(context.tenantId(), recovery, source, actorId,
                    reason, attempt, now) != 1) replayConflict();
            insertAudit(context.tenantId(), auditId,
                    "TICKET_RECOVERY_REPLAY_REQUESTED", recoveryId, "SUCCEEDED", actorId,
                    digest, null, attempt, source.correlationId(), now);
            var accepted = find(context.tenantId(), recoveryId, false).orElseThrow();
            return new Acceptance(view(accepted), false, null);
        }
        throw routeMismatch();
    }

    /**
     * 事务内锁定目标记录，使状态校验与写入基于一致快照。
     */
    private com.flowdesk.ticket.model.recovery.Recovery locked(UUID tenant, UUID recoveryId,
                                                      String direction, UUID sourceId) {
        return find(tenant, recoveryId, true)
                .filter(r -> direction.equals(r.direction()) && sourceId.equals(r.sourceRecordId()))
                .orElseThrow(TicketRecoveryServiceImpl::routeMismatch);
    }
    /** 只允许 OPEN 且原消息仍需恢复的记录进入重放，REPLAYING 表示已被其他请求取得。 */
    private static void validateReplayable(com.flowdesk.ticket.model.recovery.Recovery recovery,
                                           String sourceStatus, long expected) {
        if (!"OPEN".equals(recovery.state())) {
            if ("REPLAYING".equals(recovery.state())) replayConflict();
            throw ApiException.conflict("RECOVERY_NOT_REPLAYABLE", "恢复记录不再允许人工重放。");
        }
        if (!"RECOVERY_REQUIRED".equals(sourceStatus)) throw ApiException.conflict(
                "RECOVERY_NOT_REPLAYABLE", "原消息事实已经终态收敛。");
        if (recovery.lockVersion() != expected) throw TicketRecoverySupport.versionConflict(
                recovery.lockVersion());
    }
    /**
     * 受理提交后启动 Inbox 恢复处理，避免消费未提交状态。
     */
    private static boolean activatedInbox(com.flowdesk.ticket.model.recovery.InboxSource source) {
        return source.contractVersion() == 1 && Set.of(
                "flowdesk.workflow.instance-established.v1",
                "flowdesk.workflow.approval-finalized.v1",
                "flowdesk.incident.ticket-create-requested.v1",
                "flowdesk.incident.recovered.v1").contains(source.contractRef());
    }
    /**
     * 受理提交后唤醒原发件箱发布路径，使原消息继续投递。
     */
    private static boolean activatedOutbox(com.flowdesk.ticket.model.recovery.OutboxSource source) {
        return source.contractVersion() == 1 && Set.of(
                "flowdesk.workflow.start.v1",
                "flowdesk.incident.ticket-established.v1",
                "flowdesk.ticket.notification-requested.v1").contains(source.contractRef());
    }
    /**
     * 核对审计操作、目标和摘要，防止不同意图复用同一业务效果。
     */
    private static void validateAudit(com.flowdesk.ticket.model.recovery.Audit audit, UUID recovery,
                                      UUID actor, String digest, UUID sourceCorrelation) {
        if (!"TICKET_RECOVERY_REPLAY_REQUESTED".equals(audit.action())
                || !recovery.equals(audit.targetId()) || !"SUCCEEDED".equals(audit.outcome())
                || !"USER".equals(audit.actorType()) || !actor.equals(audit.actorId())
                || !digest.equals(audit.reason()) || audit.errorCode() != null
                || !Objects.equals(sourceCorrelation, audit.causationId())) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "同一 Idempotency-Key 已用于不同恢复意图。");
        }
    }
    /**
     * 构造恢复幂等或状态冲突，阻止不同意图复用重放结果。
     */
    private static void replayConflict() { throw ApiException.conflict(
            "RECOVERY_REPLAY_CONFLICT", "恢复记录的重放裁决已被其他请求取得。"); }
    /**
     * 构造路由不匹配结果，阻止错误主题消息执行业务副作用。
     */
    private static ApiException routeMismatch() { return ApiException.notFound(
            "RECOVERY_ROUTE_MISMATCH", "恢复记录不存在或不属于 Ticket 路由。"); }
    /**
     * 构造输入不合法的结果，供上层返回约定业务或契约错误。
     */
    private static void invalid(String field) { throw ApiException.badRequest(
            "INVALID_QUERY_PARAMETER", field + " query 参数无效。"); }
    /**
     * 返回当前对象的值，供调用方按类型契约使用。
     */
    private static String value(Object v) { return v == null ? "null" : v.toString(); }
    /**
     * 取得当前时间并规范为业务使用的时间精度。
     */
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }

    /**
     * 恢复受理事务结果，区分幂等复用与需提交后台工作的任务。
     */
    private record Acceptance(TicketRecoveryView view, boolean replayed, TicketReplayWork work) { }


    /**
     * 按租户与业务标识读取记录，供后续状态校验和响应构造。
     */
    private Optional<Recovery> find(UUID tenantId, UUID recoveryId, boolean lock) {
        return mapper.findSelect(Uuids.toBytes(tenantId), Uuids.toBytes(recoveryId), lock).stream().findFirst();
    }

    /**
     * 分页读取恢复列表事实，供统一响应与游标计算。
     */
    private List<Recovery> listFacts(UUID tenantId, String direction, String state, Boolean deadLettered,
                        String error, Instant from, Instant to, boolean ascending,
                        boolean deadSort, Instant cursorTime, UUID cursorId, int limit) {
        return mapper.listSelect(Uuids.toBytes(tenantId), direction, state, error, from, to, cursorTime, cursorId, limit, deadLettered, deadSort, ascending);
    }

    /**
     * 读取恢复项关联 Inbox 原文，保证手工重放沿用持久化事实。
     */
    private Optional<InboxSource> inboxSource(UUID tenantId, UUID inboxId, boolean lock) {
        return mapper.inboxSourceSelect(Uuids.toBytes(tenantId), Uuids.toBytes(inboxId), lock).stream().findFirst();
    }

    /**
     * 读取关联 Outbox 原始发布事实，供版本与可重放条件校验。
     */
    private Optional<OutboxSource> outboxSource(UUID tenantId, UUID outboxId, boolean lock) {
        return mapper.outboxSourceSelect(Uuids.toBytes(tenantId), Uuids.toBytes(outboxId), lock).stream().findFirst();
    }

    /**
     * 记录恢复受理或后台结算审计，保留原始人工操作身份与本轮结果。
     */
    private Optional<Audit> audit(UUID tenantId, UUID auditId) {
        return mapper.auditSelect(Uuids.toBytes(tenantId), Uuids.toBytes(auditId)).stream().findFirst();
    }

    /**
     * 在事务中受理 Inbox 重放，保留原始消息并建立本次租约。
     */
    private int acceptInbox(UUID tenantId, Recovery recovery, UUID actorId, String reason,
                    UUID attemptCorrelation, Instant now) {
        return markReplaying(tenantId, recovery, actorId, reason, attemptCorrelation, now);
    }

    /**
     * 在事务中恢复原发件箱投递，复用原发布事实而不新建业务意图。
     */
    private int acceptOutbox(UUID tenantId, Recovery recovery, OutboxSource source, UUID actorId,
                     String reason, UUID attemptCorrelation, Instant now) {
        int changed = markReplaying(tenantId, recovery, actorId, reason, attemptCorrelation, now);
        if (changed != 1) return changed;
        int outbox = mapper.acceptOutboxUpdate(Timestamp.from(now), Timestamp.from(now), Uuids.toBytes(tenantId), Uuids.toBytes(source.outboxId()), source.lockVersion());
        if (outbox != 1) throw new IllegalStateException("Ticket Outbox replay CAS lost");
        if (!"flowdesk.workflow.start.v1".equals(source.contractRef())) return 1;
        int link = mapper.acceptOutboxUpdate2(Timestamp.from(now), Uuids.toBytes(actorId), Uuids.toBytes(tenantId), Uuids.toBytes(source.ticketId()), source.linkLockVersion());
        if (link != 1) throw new IllegalStateException("Ticket WorkflowStartLink replay CAS lost");
        return 1;
    }

    /**
     * 按预期版本受理重放，记录操作人、关联标识和当前租约。
     */
    private int markReplaying(UUID tenantId, Recovery recovery, UUID actorId, String reason,
                              UUID attemptCorrelation, Instant now) {
        return mapper.markReplayingUpdate("INBOX".equals(recovery.direction())
                        ? Timestamp.from(RecoveryReplayResumer.leaseUntil(now)) : null, Timestamp.from(now), Uuids.toBytes(actorId), reason, Uuids.toBytes(attemptCorrelation), Timestamp.from(now), Uuids.toBytes(actorId), Uuids.toBytes(tenantId), Uuids.toBytes(recovery.recoveryId()), recovery.direction(), Uuids.toBytes(recovery.sourceRecordId()), recovery.lockVersion());
    }

    /**
     * 保存业务操作审计，记录目标、主体、结果和请求链路。
     */
    private void insertAudit(UUID tenantId, UUID auditId, String action, UUID recoveryId,
                     String outcome, UUID actorId, String reason, String error,
                     UUID correlation, UUID causation, Instant now) {
        mapper.insertAuditUpdate(Uuids.toBytes(tenantId), Uuids.toBytes(auditId), action, Uuids.toBytes(recoveryId), outcome, Uuids.toBytes(actorId), reason, error, Uuids.toBytes(correlation), Uuids.toBytes(causation), Timestamp.from(now));
    }

    /**
     * 将恢复快照转换为对外详情，统一展示来源、状态与操作信息。
     */
    private static TicketRecoveryView view(Recovery fact) {
        var replayedBy = fact.lastReplayedById() == null ? null
                : new TicketRecoveryView.ReplayedBy(fact.lastReplayedByType(), fact.lastReplayedById());
        return new TicketRecoveryView(fact.recoveryId(), "TICKET", fact.direction(),
                fact.sourceRecordId(), HexFormat.of().formatHex(fact.businessIntentKey()),
                fact.state(), fact.attemptCount(), fact.nextAttemptAt(), fact.lastErrorCode(),
                fact.lastErrorSummary(), fact.deadLetteredAt(), fact.recoveryRequiredAt(),
                fact.replayCount(), fact.lastReplayedAt(), replayedBy, fact.lastReplayReason(),
                fact.resolvedAt(), fact.lockVersion(), fact.createdAt(), fact.updatedAt());
    }
}
