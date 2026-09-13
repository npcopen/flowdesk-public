package com.flowdesk.ticket.recovery;

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

public final class TicketRecoveryService {
    private static final Set<String> DIRECTIONS = Set.of("INBOX", "OUTBOX");
    private static final Set<String> STATES = Set.of("OPEN", "REPLAYING", "RESOLVED");
    private static final Set<String> SORTS = Set.of(
            "-recoveryRequiredAt", "recoveryRequiredAt", "-deadLetteredAt");
    private final TicketRecoveryRepository repository;
    private final TicketCursorTokenService cursors;
    private final TransactionTemplate transactions;
    private final TicketRecoveryReplayWorker worker;

    TicketRecoveryService(TicketRecoveryRepository repository, TicketCursorTokenService cursors,
                          PlatformTransactionManager transactionManager,
                          TicketRecoveryReplayWorker worker) {
        this.repository = repository; this.cursors = cursors;
        this.transactions = new TransactionTemplate(transactionManager); this.worker = worker;
    }

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
        List<TicketRecoveryRepository.Recovery> facts = repository.list(tenant, direction, state,
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
        return new Page(facts.stream().map(TicketRecoveryRepository::view).toList(), next, normalized);
    }

    public TicketRecoveryView get(UUID recoveryId) {
        PermissionPolicy.requireCurrentPrincipal(PermissionCode.RECOVERY_READ_TENANT);
        return repository.find(TenantContextHolder.current().tenantId(), recoveryId, false)
                .map(TicketRecoveryRepository::view).orElseThrow(TicketRecoveryService::routeMismatch);
    }

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

    private Acceptance accept(TenantContext context, UUID recoveryId, UUID actorId,
                              String reason, String key, long expectedVersion) {
        UUID auditId = TicketRecoveryIdempotency.auditId(context, recoveryId, key);
        String digest = TicketRecoveryIdempotency.requestDigest(recoveryId, reason);
        TicketRecoveryRepository.Recovery candidate = repository
                .find(context.tenantId(), recoveryId, false).orElseThrow(TicketRecoveryService::routeMismatch);
        if ("INBOX".equals(candidate.direction())) {
            var source = repository.inboxSource(context.tenantId(), candidate.sourceRecordId(), true)
                    .filter(TicketRecoveryService::activatedInbox).orElseThrow(TicketRecoveryService::routeMismatch);
            var recovery = locked(context.tenantId(), recoveryId, "INBOX", source.inboxId());
            var prior = repository.audit(context.tenantId(), auditId);
            if (prior.isPresent()) {
                validateAudit(prior.get(), recoveryId, actorId, digest, source.correlationId());
                return new Acceptance(TicketRecoveryRepository.view(recovery), true, null);
            }
            validateReplayable(recovery, source.status(), expectedVersion);
            Instant now = now(); UUID attempt = UUID.randomUUID();
            if (repository.acceptInbox(context.tenantId(), recovery, actorId,
                    reason, attempt, now) != 1) replayConflict();
            repository.insertAudit(context.tenantId(), auditId,
                    "TICKET_RECOVERY_REPLAY_REQUESTED", recoveryId, "SUCCEEDED", actorId,
                    digest, null, attempt, source.correlationId(), now);
            var accepted = repository.find(context.tenantId(), recoveryId, false).orElseThrow();
            return new Acceptance(TicketRecoveryRepository.view(accepted), false,
                    new ReplayWork(context.tenantId(), recoveryId, source.inboxId(), actorId,
                            reason, attempt, source.correlationId()));
        }
        if ("OUTBOX".equals(candidate.direction())) {
            var source = repository.outboxSource(context.tenantId(), candidate.sourceRecordId(), true)
                    .filter(TicketRecoveryService::activatedOutbox).orElseThrow(TicketRecoveryService::routeMismatch);
            var recovery = locked(context.tenantId(), recoveryId, "OUTBOX", source.outboxId());
            var prior = repository.audit(context.tenantId(), auditId);
            if (prior.isPresent()) {
                validateAudit(prior.get(), recoveryId, actorId, digest, source.correlationId());
                return new Acceptance(TicketRecoveryRepository.view(recovery), true, null);
            }
            validateReplayable(recovery, source.status(), expectedVersion);
            if ("flowdesk.workflow.start.v1".equals(source.contractRef())
                    && !"RECOVERY_REQUIRED".equals(source.linkStatus())) throw ApiException.conflict(
                    "RECOVERY_NOT_REPLAYABLE", "WorkflowStartLink 已经收敛。");
            Instant now = now(); UUID attempt = UUID.randomUUID();
            if (repository.acceptOutbox(context.tenantId(), recovery, source, actorId,
                    reason, attempt, now) != 1) replayConflict();
            repository.insertAudit(context.tenantId(), auditId,
                    "TICKET_RECOVERY_REPLAY_REQUESTED", recoveryId, "SUCCEEDED", actorId,
                    digest, null, attempt, source.correlationId(), now);
            var accepted = repository.find(context.tenantId(), recoveryId, false).orElseThrow();
            return new Acceptance(TicketRecoveryRepository.view(accepted), false, null);
        }
        throw routeMismatch();
    }

    private TicketRecoveryRepository.Recovery locked(UUID tenant, UUID recoveryId,
                                                      String direction, UUID sourceId) {
        return repository.find(tenant, recoveryId, true)
                .filter(r -> direction.equals(r.direction()) && sourceId.equals(r.sourceRecordId()))
                .orElseThrow(TicketRecoveryService::routeMismatch);
    }
    private static void validateReplayable(TicketRecoveryRepository.Recovery recovery,
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
    private static boolean activatedInbox(TicketRecoveryRepository.InboxSource source) {
        return source.contractVersion() == 1 && Set.of(
                "flowdesk.workflow.instance-established.v1",
                "flowdesk.workflow.approval-finalized.v1",
                "flowdesk.incident.ticket-create-requested.v1",
                "flowdesk.incident.recovered.v1").contains(source.contractRef());
    }
    private static boolean activatedOutbox(TicketRecoveryRepository.OutboxSource source) {
        return source.contractVersion() == 1 && Set.of(
                "flowdesk.workflow.start.v1",
                "flowdesk.incident.ticket-established.v1").contains(source.contractRef());
    }
    private static void validateAudit(TicketRecoveryRepository.Audit audit, UUID recovery,
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
    private static void replayConflict() { throw ApiException.conflict(
            "RECOVERY_REPLAY_CONFLICT", "恢复记录的重放裁决已被其他请求取得。"); }
    private static ApiException routeMismatch() { return ApiException.notFound(
            "RECOVERY_ROUTE_MISMATCH", "恢复记录不存在或不属于 Ticket 路由。"); }
    private static void invalid(String field) { throw ApiException.badRequest(
            "INVALID_QUERY_PARAMETER", field + " query 参数无效。"); }
    private static String value(Object v) { return v == null ? "null" : v.toString(); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }

    public record Page(List<TicketRecoveryView> items, String nextPageToken, String sort) { }
    public record ReplayResult(TicketRecoveryView recovery, boolean replayed) { }
    private record Acceptance(TicketRecoveryView view, boolean replayed, ReplayWork work) { }
    record ReplayWork(UUID tenantId, UUID recoveryId, UUID sourceRecordId, UUID actorId,
                      String reason, UUID attemptCorrelationId, UUID sourceCorrelationId) { }
}
