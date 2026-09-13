package com.flowdesk.notification.service;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.notification.NotificationWireCodec;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.notification.api.NotificationDtos;
import com.flowdesk.notification.event.NotificationConsumeResult;
import com.flowdesk.notification.event.NotificationContractViolation;
import com.flowdesk.notification.event.NotificationEnvelopeGuard;
import com.flowdesk.notification.event.NotificationContract;
import com.flowdesk.notification.event.NotificationTransportMessage;
import com.flowdesk.notification.repository.NotificationRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Protected Notification Inbox recovery read/replay facade. */
@Service
public class NotificationRecoveryService {
    private final NotificationRepository repository;
    private final NotificationService consumer;
    private final NotificationCursorTokenService cursors;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final NotificationEnvelopeGuard guard;

    public NotificationRecoveryService(NotificationRepository repository,
                                       NotificationService consumer,
                                       NotificationCursorTokenService cursors,
                                       PlatformTransactionManager transactionManager) {
        this(repository, consumer, cursors, transactionManager, Clock.systemUTC(),
                new NotificationEnvelopeGuard());
    }

    /** Compatibility constructor for narrow fixtures with an explicit Clock. */
    public NotificationRecoveryService(NotificationRepository repository,
                                       NotificationService consumer,
                                       NotificationCursorTokenService cursors,
                                       PlatformTransactionManager transactionManager,
                                       Clock clock) {
        this(repository, consumer, cursors, transactionManager, clock,
                new NotificationEnvelopeGuard());
    }

    @Autowired
    public NotificationRecoveryService(
                                       @Qualifier("notificationRecoveryRepository") NotificationRepository repository,
                                       NotificationService consumer,
                                       NotificationCursorTokenService cursors,
                                       @Qualifier("notificationRecoveryTransactionManager") PlatformTransactionManager transactionManager,
                                       Clock clock,
                                       NotificationEnvelopeGuard guard) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.guard = Objects.requireNonNull(guard, "guard");
    }

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
        List<NotificationRepository.RecoveryRow> rows = repository.listRecoveries(
                NotificationSupport.context().tenantId(), direction, state, deadLettered, errorCode,
                from, to, ascending, cursor == null ? null : cursor.value(),
                cursor == null ? null : cursor.id(), pageSize + 1);
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
        return new Page<>(rows.stream().map(NotificationRecoveryService::response).toList(), next, normalized);
    }

    public NotificationDtos.MessageRecoveryResponse get(UUID recoveryId) {
        NotificationSupport.require(PermissionCode.RECOVERY_READ_TENANT);
        return repository.findRecovery(NotificationSupport.context().tenantId(), recoveryId, false)
                .map(NotificationRecoveryService::response)
                .orElseThrow(() -> ApiException.notFound("RECOVERY_ROUTE_MISMATCH", "Recovery 不存在或不属于该路由。"));
    }

    public NotificationDtos.MessageRecoveryResponse replay(UUID recoveryId,
                                                            NotificationDtos.ReplayCommand command,
                                                            String ifMatch, String idempotencyKey) {
        NotificationSupport.require(PermissionCode.RECOVERY_REPLAY_TENANT);
        String key = NotificationSupport.idempotency(idempotencyKey);
        if (command == null || command.reason() == null || command.reason().isBlank()) {
            throw ApiException.unprocessable("RECOVERY_REASON_REQUIRED", "重放必须提供原因。", List.of());
        }
        long expected = NotificationSupport.ifMatch(ifMatch);
        UUID tenant = NotificationSupport.context().tenantId();
        UUID actor = NotificationSupport.actorId();
        NotificationRepository.RecoveryRow current = repository.findRecovery(tenant, recoveryId, false)
                .orElseThrow(() -> ApiException.notFound("RECOVERY_ROUTE_MISMATCH", "Recovery 不存在或不属于该路由。"));
        if (!"INBOX".equals(current.direction())) {
            throw ApiException.notFound("RECOVERY_ROUTE_MISMATCH", "Recovery 路由不匹配。");
        }
        UUID replayAuditId = stableUuid(NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-RECOVERY-V1", tenant, recoveryId, key));

        // Claim, source validation and the replay audit are committed together.
        // A repeated command key therefore becomes a read-only replay.
        ReplayClaim claim;
        try {
            claim = transactions.execute(tx -> {
                NotificationRepository.RecoveryRow locked = repository.findRecovery(tenant, recoveryId, true)
                        .orElseThrow(() -> ApiException.notFound("RECOVERY_ROUTE_MISMATCH", "Recovery 不存在或不属于该路由。"));
                Optional<NotificationRepository.AuditRow> priorAudit = repository.findAudit(tenant, replayAuditId);
                if (priorAudit.isPresent()) {
                    // The first request may already have settled the source
                    // Inbox to PROCESSED/REJECTED.  Idempotent replay is a
                    // read-only result and must never re-validate or re-drive
                    // that terminal source.
                    ReplayAuditOutcome outcome = validateReplayAudit(priorAudit.get(), replayAuditId,
                            recoveryId, actor, command.reason(), repository.findInbox(
                                    tenant, locked.sourceRecordId(), false)
                                    .map(NotificationRepository.InboxRow::correlationId).orElse(null));
                    if (outcome == ReplayAuditOutcome.NON_REPLAYABLE) {
                        throw ApiException.conflict("RECOVERY_NOT_REPLAYABLE",
                                "原始 Inbox 源事实不完整，不能安全重放。");
                    }
                    return new ReplayClaim(locked, null, true);
                }
                if ("RESOLVED".equals(locked.state())) {
                    throw ApiException.conflict("RECOVERY_NOT_REPLAYABLE",
                            "该恢复记录已经解决，且无法确认当前幂等请求属于原始重放。");
                }
                if (locked.lockVersion() != expected) {
                    throw new ApiException(org.springframework.http.HttpStatus.PRECONDITION_FAILED,
                            "RESOURCE_VERSION_CONFLICT", "Recovery 已更新，请刷新后重试。", false, List.of(),
                            java.util.Map.of(org.springframework.http.HttpHeaders.ETAG,
                                    "\"lv:" + locked.lockVersion() + "\""));
                }
                if (!"OPEN".equals(locked.state())) {
                    throw ApiException.conflict("RECOVERY_ALREADY_REPLAYING",
                            "Recovery 已被其他重放操作领取。");
                }
                NotificationRepository.InboxRow source = repository.findInbox(
                        tenant, locked.sourceRecordId(), true)
                        .orElseThrow(() -> new NonReplayableSourceException("REPLAY_SOURCE_MISSING"));
                NotificationTransportMessage delivery = toDelivery(source);
                UUID attemptCorrelation = NotificationSupport.context().correlationId();
                Instant claimedAt = now();
                if (repository.claimReplay(tenant, recoveryId, locked.lockVersion(), actor,
                        attemptCorrelation, command.reason(), claimedAt) != 1) {
                    throw new ApiException(org.springframework.http.HttpStatus.PRECONDITION_FAILED,
                            "RESOURCE_VERSION_CONFLICT", "Recovery 已被其他操作领取。", false);
                }
                insertAuditOnce(tenant, replayAuditId, "NOTIFICATION_RECOVERY_REPLAY_CLAIMED",
                        "MESSAGE_RECOVERY", recoveryId, "SUCCEEDED", "USER", actor,
                        command.reason(), null, attemptCorrelation, source.correlationId(), claimedAt);
                return new ReplayClaim(repository.findRecovery(tenant, recoveryId, false).orElseThrow(),
                        delivery, false);
            });
        }
        catch (NonReplayableSourceException invalidSource) {
            // Source validation intentionally runs before the claim transaction
            // can commit.  Record the terminal decision in a fresh transaction
            // so an incomplete legacy row cannot be retried forever.
            markNonReplayable(tenant, recoveryId, invalidSource.code(), replayAuditId,
                    actor, command.reason());
            throw ApiException.conflict("RECOVERY_NOT_REPLAYABLE",
                    "原始 Inbox 源事实不完整，不能安全重放。");
        }
        if (claim == null || claim.idempotent() || !"REPLAYING".equals(claim.recovery().state())) {
            return response(claim == null ? current : claim.recovery());
        }

        NotificationConsumeResult result;
        try {
            result = consumer.consume(Objects.requireNonNull(claim.delivery(), "replay delivery"));
        }
        catch (RuntimeException unavailable) {
            reopenAfterReplay(tenant, recoveryId, "REPLAY_CONSUME_UNAVAILABLE",
                    "Notification replay consumer unavailable");
            return getWithoutPermission(recoveryId);
        }
        if (result == null || result.disposition() == NotificationConsumeResult.Disposition.RETRY) {
            String code = result == null || result.code() == null
                    ? "REPLAY_CONSUME_RETRY" : safeCode(result.code());
            reopenAfterReplay(tenant, recoveryId, code, "Notification replay returned retry");
        }
        else {
            // The consumer normally resolves the source Recovery. Resolve again
            // so an ACK from an already-terminal source cannot strand REPLAYING.
            transactions.execute(tx -> resolveAfterReplay(tenant, recoveryId, now()));
        }
        return getWithoutPermission(recoveryId);
    }

    private NotificationDtos.MessageRecoveryResponse getWithoutPermission(UUID id) {
        return repository.findRecovery(NotificationSupport.context().tenantId(), id, false)
                .map(NotificationRecoveryService::response).orElseThrow();
    }

    private NotificationTransportMessage toDelivery(NotificationRepository.InboxRow inbox) {
        if (inbox == null || !"RECOVERY_REQUIRED".equals(inbox.status())
                || !"INBOX".equals(inbox.direction())
                || inbox.messageId() == null || inbox.messageKey() == null
                || inbox.businessIntentKey() == null || inbox.publicationKey() == null
                || inbox.bodyBytes() == null || inbox.bodySha256() == null
                || inbox.aggregateId() == null || inbox.correlationId() == null
                || inbox.occurredAt() == null) {
            throw new NonReplayableSourceException("REPLAY_SOURCE_INCOMPLETE");
        }
        var binding = bindingFor(inbox);
        if (binding == null || !binding.matchesRoute(inbox.topic(), inbox.consumerGroup(),
                inbox.producerService(), inbox.contractRef(), inbox.contractVersion(),
                inbox.aggregateType())
                || !Objects.equals(inbox.logicalDestination(),
                        inbox.topic() + "|" + inbox.consumerGroup())
                || !inbox.messageId().toString().equals(inbox.messageKey())
                || inbox.bodyBytes().length == 0
                || inbox.bodyBytes().length > NotificationContract.MAX_BODY_BYTES
                || inbox.bodySha256().length != 32
                || !java.security.MessageDigest.isEqual(
                        NotificationWireCodec.sha256(inbox.bodyBytes()), inbox.bodySha256())
                || inbox.businessIntentKey().length != 32
                || inbox.publicationKey().length != 32
                || !inbox.messageId().equals(NotificationWireCodec.uuidFromKey(inbox.publicationKey()))) {
            throw new NonReplayableSourceException("REPLAY_SOURCE_ROUTE_OR_DIGEST_INVALID");
        }
        java.util.Map<String, String> props = new java.util.LinkedHashMap<>();
        props.put("fd_envelope_version", "1"); props.put("fd_event_type", binding.eventType());
        props.put("fd_contract_ref", inbox.contractRef()); props.put("fd_contract_version", Integer.toString(inbox.contractVersion()));
        props.put("fd_producer", inbox.producerService());
        props.put("fd_tenant_id", NotificationSupport.context().tenantId().toString());
        props.put("fd_message_id", inbox.messageId().toString());
        props.put("fd_business_intent_key", HexFormat.of().formatHex(inbox.businessIntentKey()));
        props.put("fd_publication_key", HexFormat.of().formatHex(inbox.publicationKey()));
        props.put("fd_aggregate_type", inbox.aggregateType()); props.put("fd_aggregate_id", inbox.aggregateId().toString());
        props.put("fd_correlation_id", inbox.correlationId().toString());
        if (inbox.causationId() != null) props.put("fd_causation_id", inbox.causationId().toString());
        props.put("fd_occurred_at", NotificationWireCodec.format(inbox.occurredAt()));
        props.put("fd_body_sha256", HexFormat.of().formatHex(inbox.bodySha256()));
        NotificationTransportMessage delivery = new NotificationTransportMessage(
                inbox.topic(), inbox.consumerGroup(), inbox.messageKey(), null,
                inbox.bodyBytes(), props);
        try {
            // Re-run the frozen wire guard before claiming the recovery. A
            // trusted payload rejection is replayable because the consumer
            // will persist a terminal REJECTED Inbox; a pre-trust failure is
            // not safely reconstructable and must be closed instead.
            guard.validateMain(delivery);
        }
        catch (NotificationContractViolation invalid) {
            if (!invalid.hasTrustedEnvelope()) {
                throw new NonReplayableSourceException("REPLAY_SOURCE_ENVELOPE_INVALID");
            }
        }
        return delivery;
    }

    private static com.flowdesk.common.notification.NotificationEventContract.Binding bindingFor(
            NotificationRepository.InboxRow inbox) {
        for (var binding : NotificationContract.bindings()) {
            if (Objects.equals(binding.producerService(), inbox.producerService())
                    && Objects.equals(binding.contractRef(), inbox.contractRef())
                    && Objects.equals(binding.topic(), inbox.topic())
                    && Objects.equals(binding.consumerGroup(), inbox.consumerGroup())) {
                return binding;
            }
        }
        return null;
    }

    private void reopenAfterReplay(UUID tenant, UUID recoveryId, String code, String summary) {
        transactions.execute(tx -> {
            NotificationRepository.RecoveryRow row = repository.findRecovery(tenant, recoveryId, true)
                    .orElseThrow(() -> new IllegalStateException("Notification Recovery row is missing"));
            if ("RESOLVED".equals(row.state()) || "OPEN".equals(row.state())) return null;
            int changed = repository.reopenRecovery(tenant, recoveryId, row.lockVersion(),
                    safeCode(code), summary, now());
            if (changed != 1) {
                throw new IllegalStateException("Notification Recovery reopen race");
            }
            return null;
        });
    }

    private Integer resolveAfterReplay(UUID tenant, UUID recoveryId, Instant at) {
        NotificationRepository.RecoveryRow row = repository.findRecovery(tenant, recoveryId, true)
                .orElseThrow(() -> new IllegalStateException("Notification Recovery row is missing"));
        if ("RESOLVED".equals(row.state())) return 0;
        int changed = repository.resolveRecoveryIfSourceTerminal(tenant, recoveryId,
                row.lockVersion(), at);
        if (changed == 1) return changed;
        // Do not strand a Replay claim merely because an unexpected ACK did
        // not produce a terminal Inbox mutation. Preserve the Recovery for a
        // later explicit retry instead of declaring it resolved.
        int reopened = repository.reopenRecovery(tenant, recoveryId, row.lockVersion(),
                "REPLAY_ACK_SOURCE_NOT_TERMINAL",
                "Replay ACK did not produce a terminal Notification Inbox source.", at);
        if (reopened != 1) {
            throw new IllegalStateException("Notification Recovery resolve/reopen race");
        }
        return 0;
    }

    /** Deterministic replay audit insert with immutable-content verification. */
    private void insertAuditOnce(UUID tenantId, UUID auditId, String actionCode,
                                 String targetType, UUID targetId, String outcome,
                                 String actorType, UUID actorId, String reason,
                                 String errorCode, UUID correlationId, UUID causationId,
                                 Instant occurredAt) {
        Optional<NotificationRepository.AuditRow> existing = repository.findAudit(tenantId, auditId);
        if (existing.isPresent()) {
            validateAudit(existing.get(), auditId, actionCode, targetType, targetId, outcome,
                    actorType, actorId, reason, errorCode, correlationId, causationId);
            return;
        }
        try {
            repository.insertAudit(tenantId, auditId, actionCode, targetType, targetId, outcome,
                    actorType, actorId, reason, errorCode, correlationId, causationId, occurredAt);
        }
        catch (DuplicateKeyException duplicate) {
            NotificationRepository.AuditRow raced = repository.findAudit(tenantId, auditId)
                    .orElseThrow(() -> duplicate);
            validateAudit(raced, auditId, actionCode, targetType, targetId, outcome,
                    actorType, actorId, reason, errorCode, correlationId, causationId);
        }
    }

    private static void validateAudit(NotificationRepository.AuditRow row, UUID auditId,
                                      String actionCode, String targetType, UUID targetId,
                                      String outcome, String actorType, UUID actorId,
                                      String reason, String errorCode, UUID correlationId,
                                      UUID causationId) {
        if (!Objects.equals(row.auditId(), auditId)
                || !Objects.equals(row.actionCode(), actionCode)
                || !Objects.equals(row.targetType(), targetType)
                || !Objects.equals(row.targetId(), targetId)
                || !Objects.equals(row.outcome(), outcome)
                || !Objects.equals(row.actorType(), actorType)
                || !Objects.equals(row.actorId(), actorId)
                || !Objects.equals(row.reason(), reason)
                || !Objects.equals(row.errorCode(), errorCode)
                || !Objects.equals(row.correlationId(), correlationId)
                || !Objects.equals(row.causationId(), causationId)) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "恢复幂等键已用于不同审计意图。");
        }
    }

    private void markNonReplayable(UUID tenant, UUID recoveryId, String sourceCode,
                                   UUID replayAuditId, UUID actor, String replayReason) {
        String code = "NON_REPLAYABLE";
        String summary = "Persisted Notification Inbox source failed immutable replay validation.";
        transactions.executeWithoutResult(tx -> {
            NotificationRepository.RecoveryRow row = repository.findRecovery(tenant, recoveryId, true)
                    .orElseThrow(() -> ApiException.notFound("RECOVERY_ROUTE_MISMATCH",
                            "Recovery 不存在或不属于该路由。"));
            UUID sourceCorrelation = repository.findInbox(tenant, row.sourceRecordId(), false)
                    .map(NotificationRepository.InboxRow::correlationId).orElse(null);
            if ("RESOLVED".equals(row.state())) {
                repository.findAudit(tenant, replayAuditId).ifPresent(audit ->
                        validateReplayAudit(audit, replayAuditId, recoveryId, actor,
                                replayReason, sourceCorrelation));
                return;
            }
            Instant at = now();
            if (repository.markNonReplayable(tenant, recoveryId, row.lockVersion(), code,
                    sourceCode == null ? summary : summary + " (" + safeCode(sourceCode) + ")", at) != 1) {
                throw new IllegalStateException("Notification Recovery non-replayable CAS lost");
            }
            UUID auditId = stableUuid(NotificationWireCodec.deriveKey(
                    "FD-NOTIFICATION-NONREPLAYABLE-AUDIT-V1", tenant, recoveryId));
            insertAuditOnce(tenant, auditId, "NOTIFICATION_RECOVERY_NON_REPLAYABLE",
                    "MESSAGE_RECOVERY", recoveryId, "FAILED", "SERVICE", null,
                    summary, code, NotificationSupport.context().correlationId(), null, at);
            insertAuditOnce(tenant, replayAuditId, "NOTIFICATION_RECOVERY_REPLAY_REJECTED",
                    "MESSAGE_RECOVERY", recoveryId, "REJECTED", "USER", actor,
                    replayReason, code, NotificationSupport.context().correlationId(),
                    sourceCorrelation, at);
        });
    }

    private static String safeCode(String code) {
        return code != null && code.matches("[A-Z][A-Z0-9_:.]{0,95}")
                ? code : "REPLAY_CONSUME_RETRY";
    }

    private static UUID stableUuid(byte[] key) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(
                java.util.Arrays.copyOf(key, 16));
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static ReplayAuditOutcome validateReplayAudit(NotificationRepository.AuditRow row,
                                                            UUID auditId, UUID recoveryId,
                                                            UUID actor, String reason,
                                                            UUID sourceCorrelation) {
        if (!auditId.equals(row.auditId())
                || !"MESSAGE_RECOVERY".equals(row.targetType())
                || !recoveryId.equals(row.targetId())
                || !"USER".equals(row.actorType())
                || !actor.equals(row.actorId()) || !Objects.equals(reason, row.reason())
                || row.correlationId() == null
                || (sourceCorrelation != null
                && !Objects.equals(sourceCorrelation, row.causationId()))) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "同一 Idempotency-Key 已用于不同恢复意图。");
        }
        if ("NOTIFICATION_RECOVERY_REPLAY_CLAIMED".equals(row.actionCode())
                && "SUCCEEDED".equals(row.outcome()) && row.errorCode() == null) {
            return ReplayAuditOutcome.CLAIMED;
        }
        if ("NOTIFICATION_RECOVERY_REPLAY_REJECTED".equals(row.actionCode())
                && "REJECTED".equals(row.outcome()) && "NON_REPLAYABLE".equals(row.errorCode())) {
            return ReplayAuditOutcome.NON_REPLAYABLE;
        }
        throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "同一 Idempotency-Key 已用于不同恢复意图。");
    }

    private record ReplayClaim(NotificationRepository.RecoveryRow recovery,
                               NotificationTransportMessage delivery,
                               boolean idempotent) { }

    private enum ReplayAuditOutcome { CLAIMED, NON_REPLAYABLE }

    private static final class NonReplayableSourceException extends RuntimeException {
        private NonReplayableSourceException(String code) { super(code); }
        private String code() { return getMessage(); }
    }

    private static int parseSize(String raw) {
        if (raw == null) return 20;
        try { int v = Integer.parseInt(raw); if (v >= 1 && v <= 100) return v; }
        catch (NumberFormatException ignored) { }
        throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "pageSize 必须为 1..100。");
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    private static NotificationDtos.MessageRecoveryResponse response(NotificationRepository.RecoveryRow row) {
        return new NotificationDtos.MessageRecoveryResponse(row.recoveryId(), "NOTIFICATION", row.direction(),
                row.sourceRecordId(), HexFormat.of().formatHex(row.businessIntentKey()), row.state(),
                row.attemptCount(), row.nextAttemptAt(), row.lastErrorCode(), row.lastErrorSummary(),
                row.deadLetteredAt(), row.recoveryRequiredAt(), row.replayCount(), row.lastReplayedAt(),
                row.lastReplayedByType() == null ? null : new NotificationDtos.ReplayActor(
                        row.lastReplayedByType(), row.lastReplayedById()), row.lastReplayReason(),
                row.lastReplayCorrelationId(), row.resolvedAt(), row.lockVersion(), row.createdAt(), row.updatedAt());
    }

    public record Page<T>(List<T> items, String nextPageToken, String sort) { }
}
