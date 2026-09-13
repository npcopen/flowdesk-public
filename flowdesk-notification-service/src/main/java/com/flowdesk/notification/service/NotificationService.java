package com.flowdesk.notification.service;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.notification.NotificationWireCodec;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.notification.api.NotificationDtos;
import com.flowdesk.notification.event.AcceptedNotification;
import com.flowdesk.notification.event.NotificationContractViolation;
import com.flowdesk.notification.event.NotificationEnvelope;
import com.flowdesk.notification.event.NotificationEnvelopeGuard;
import com.flowdesk.notification.event.NotificationConsumeResult;
import com.flowdesk.notification.event.NotificationTransportMessage;
import com.flowdesk.notification.repository.NotificationRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Notification terminal consumer and human read-state use cases. */
@Service
public class NotificationService {
    private final NotificationRepository repository;
    private final NotificationEnvelopeGuard guard;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public NotificationService(NotificationRepository repository,
                               NotificationEnvelopeGuard guard,
                               PlatformTransactionManager transactionManager) {
        this(repository, guard, transactionManager, Clock.systemUTC());
    }

    @Autowired
    public NotificationService(NotificationRepository repository,
                               NotificationEnvelopeGuard guard,
                               @Qualifier("transactionManager") PlatformTransactionManager transactionManager,
                               Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Main-queue entry.  Transport poison is safely ACKed before a tenant is trusted. */
    public NotificationConsumeResult consume(NotificationTransportMessage delivery) {
        final AcceptedNotification message;
        try {
            message = guard.validateMain(delivery);
        }
        catch (NotificationContractViolation violation) {
            if (!violation.hasTrustedEnvelope()) {
                // No tenant context is installed for a poison envelope.  This
                // ACK is intentionally only a transport disposition.
                return NotificationConsumeResult.ack(violation.code());
            }
            NotificationEnvelope envelope = violation.envelope().orElseThrow();
            TenantContext context = TenantContext.ofValidatedBrokerMessage(
                    envelope.tenantId(), envelope.binding().producerService(),
                    envelope.correlationId());
            try (TenantContextScope ignored = TenantContextScope.open(context)) {
                try {
                    NotificationConsumeResult result = transactions.execute(tx ->
                            rejectTrustedEnvelope(envelope, violation.code()));
                    return Objects.requireNonNull(result);
                }
                catch (DataAccessException | TransactionException | IllegalStateException transientFailure) {
                    // A trusted rejection is still a durable Inbox fact; do
                    // not ACK until that fact and its audit have committed.
                    return NotificationConsumeResult.retry("NOTIFICATION_DB_UNAVAILABLE");
                }
            }
        }
        TenantContext context = TenantContext.ofValidatedBrokerMessage(
                message.tenantId(), message.binding().producerService(), message.correlationId());
        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            try {
                NotificationConsumeResult result = transactions.execute(tx -> process(message));
                return Objects.requireNonNull(result);
            }
            catch (DataAccessException | TransactionException | IllegalStateException transientFailure) {
                return NotificationConsumeResult.retry("NOTIFICATION_DB_UNAVAILABLE");
            }
        }
    }

    /** DLQ acceptor entry.  It persists the complete source before acknowledging. */
    public NotificationConsumeResult acceptDlq(NotificationTransportMessage delivery) {
        final AcceptedNotification message;
        try { message = guard.validateDlq(delivery); }
        catch (NotificationContractViolation poison) { return NotificationConsumeResult.ack(poison.code()); }
        TenantContext context = TenantContext.ofValidatedBrokerMessage(
                message.tenantId(), message.binding().producerService(), message.correlationId());
        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            try {
                NotificationConsumeResult result = transactions.execute(tx -> persistDlq(message));
                return Objects.requireNonNull(result);
            }
            catch (DataAccessException | TransactionException | IllegalStateException transientFailure) {
                return NotificationConsumeResult.retry("NOTIFICATION_DLQ_PERSISTENCE_UNAVAILABLE");
            }
        }
    }

    private NotificationConsumeResult persistDlq(AcceptedNotification message) {
        Instant now = now();
        NotificationRepository.Duplicate duplicate = repository.findDuplicate(message, true);
        if (duplicate.kind() == NotificationRepository.DuplicateKind.TERMINAL) {
            return NotificationConsumeResult.ack("NOTIFICATION_DUPLICATE");
        }
        if (duplicate.kind() == NotificationRepository.DuplicateKind.CONFLICT) {
            insertAuditOnce(message.tenantId(), auditId("FD-NOTIFICATION-DLQ-CONFLICT-AUDIT-V1",
                            message.tenantId(), message.messageId(), duplicate.conflictCode()),
                    "NOTIFICATION_INBOX_CONFLICT", "MESSAGE_RECOVERY", null, "REJECTED",
                    "SERVICE", null, "Message identity/content conflict", duplicate.conflictCode(),
                    message.correlationId(), message.causationId(), now);
            return NotificationConsumeResult.ack(duplicate.conflictCode());
        }
        if (duplicate.kind() == NotificationRepository.DuplicateKind.RECOVERABLE) {
            // A RECOVERY_REQUIRED Inbox is not sufficient evidence by itself.
            // The first DLQ transaction must have committed the paired
            // deterministic Recovery row as well.  Refuse to ACK an orphan so
            // the broker can retry and complete the pair atomically.
            requireDlqRecovery(message, duplicate.inbox());
            return NotificationConsumeResult.ack("NOTIFICATION_RECOVERY_ALREADY_RECORDED");
        }
        // The source identity is deterministic.  A redelivered DLQ record must
        // never create a second Inbox/Recovery pair or a second audit anchor.
        UUID inboxId = stableUuid(NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-INBOX-V1", message.tenantId(), message.messageId()));
        try {
            repository.insertRecoveryInbox(message, inboxId, "BROKER_DELIVERY_EXHAUSTED", now);
        }
        catch (DuplicateKeyException racedInsert) {
            NotificationRepository.Duplicate raced = repository.findDuplicate(message, true);
            if (raced.kind() == NotificationRepository.DuplicateKind.CONFLICT) {
                insertAuditOnce(message.tenantId(), auditId("FD-NOTIFICATION-DLQ-CONFLICT-AUDIT-V1",
                                message.tenantId(), message.messageId(), raced.conflictCode()),
                        "NOTIFICATION_INBOX_CONFLICT", "MESSAGE_RECOVERY", null, "REJECTED",
                        "SERVICE", null, "Message identity/content conflict", raced.conflictCode(),
                        message.correlationId(), message.causationId(), now);
                return NotificationConsumeResult.ack(raced.conflictCode());
            }
            if (raced.kind() == NotificationRepository.DuplicateKind.TERMINAL) {
                return NotificationConsumeResult.ack("NOTIFICATION_DUPLICATE");
            }
            if (raced.kind() == NotificationRepository.DuplicateKind.RECOVERABLE) {
                requireDlqRecovery(message, raced.inbox());
                return NotificationConsumeResult.ack("NOTIFICATION_RECOVERY_ALREADY_RECORDED");
            }
            throw racedInsert;
        }
        UUID recoveryId = stableUuid(NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-RECOVERY-V1", message.tenantId(), inboxId));
        repository.insertRecovery(message.tenantId(), recoveryId, inboxId,
                message.businessIntentKey(), "BROKER_DELIVERY_EXHAUSTED",
                "Notification delivery exhausted bounded retries", now);
        insertAuditOnce(message.tenantId(), auditId("FD-NOTIFICATION-DLQ-RECOVERY-AUDIT-V1",
                        message.tenantId(), message.messageId()),
                "NOTIFICATION_INBOX_RECOVERY_REQUIRED", "MESSAGE_RECOVERY", inboxId, "FAILED",
                "SERVICE", null, "Notification delivery exhausted bounded retries",
                "BROKER_DELIVERY_EXHAUSTED", message.correlationId(), message.causationId(), now);
        return NotificationConsumeResult.ack("NOTIFICATION_RECOVERY_REQUIRED");
    }

    /**
     * Validate the one-to-one Inbox/Recovery pair before absorbing a duplicate
     * DLQ delivery.  Recovery state and error details are mutable after a
     * human replay, so only the deterministic identity and source facts are
     * compared here.
     */
    private void requireDlqRecovery(AcceptedNotification message,
                                    NotificationRepository.InboxRow inbox) {
        if (inbox == null || !"RECOVERY_REQUIRED".equals(inbox.status())
                || !message.tenantId().equals(NotificationSupport.context().tenantId())) {
            throw new IllegalStateException("Notification DLQ Inbox source is not recoverable");
        }
        UUID expectedRecoveryId = stableUuid(NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-RECOVERY-V1", message.tenantId(), inbox.inboxId()));
        NotificationRepository.RecoveryRow recovery = repository.findRecoveryBySource(
                        message.tenantId(), inbox.inboxId(), true)
                .orElseThrow(() -> new IllegalStateException(
                        "Notification DLQ Inbox has no paired Recovery"));
        if (!expectedRecoveryId.equals(recovery.recoveryId())
                || !"INBOX".equals(recovery.direction())
                || !inbox.inboxId().equals(recovery.sourceRecordId())
                || !Arrays.equals(message.businessIntentKey(), recovery.businessIntentKey())
                || recovery.recoveryRequiredAt() == null
                || !Set.of("OPEN", "REPLAYING", "RESOLVED").contains(recovery.state())
                || recovery.attemptCount() < NotificationEventContract.DELIVERY_ATTEMPTS) {
            throw new IllegalStateException("Notification DLQ Inbox/Recovery identity conflict");
        }
    }

    private NotificationConsumeResult process(AcceptedNotification message) {
        Instant now = now();
        NotificationRepository.Duplicate duplicate = repository.findDuplicate(message, true);
        if (duplicate.kind() == NotificationRepository.DuplicateKind.TERMINAL) {
            return NotificationConsumeResult.ack("NOTIFICATION_DUPLICATE");
        }
        if (duplicate.kind() == NotificationRepository.DuplicateKind.CONFLICT) {
            insertAuditOnce(message.tenantId(), auditId("FD-NOTIFICATION-CONFLICT-AUDIT-V1",
                            message.tenantId(), message.messageId(), duplicate.conflictCode()),
                    "NOTIFICATION_INBOX_CONFLICT", "MESSAGE_RECOVERY", null, "REJECTED",
                    "SERVICE", null, "Message identity/content conflict", duplicate.conflictCode(),
                    message.correlationId(), message.causationId(), now);
            return NotificationConsumeResult.ack(duplicate.conflictCode());
        }
        UUID notificationId = stableUuid(message.businessIntentKey());
        try {
            repository.insertNotification(message.tenantId(), notificationId, message.recipientUserId(),
                    message.notificationKind(), message.binding().producerService(),
                    message.sourceEffectKey(), message.sourceRefType(), message.sourceRefId(),
                    message.title(), message.body(), now);
        }
        catch (DuplicateKeyException duplicateNotification) {
            NotificationRepository.NotificationRow existing = repository.findByEffect(
                    message.tenantId(), message.recipientUserId(), message.binding().producerService(),
                    message.sourceEffectKey(), message.notificationKind(), true).orElse(null);
            if (existing == null || !sameNotification(existing, message)) {
                insertAuditOnce(message.tenantId(), auditId("FD-NOTIFICATION-EFFECT-CONFLICT-AUDIT-V1",
                                message.tenantId(), message.businessIntentKey()),
                        "NOTIFICATION_EFFECT_CONFLICT", "NOTIFICATION", notificationId, "REJECTED",
                        "SERVICE", null, "Notification effect key/content conflict",
                        "NOTIFICATION_EFFECT_CONFLICT", message.correlationId(), message.causationId(), now);
                return NotificationConsumeResult.ack("NOTIFICATION_EFFECT_CONFLICT");
            }
            notificationId = existing.notificationId();
        }
        NotificationRepository.Duplicate effectiveDuplicate = duplicate;
        if (effectiveDuplicate.kind() == NotificationRepository.DuplicateKind.NONE) {
            UUID inboxId = stableUuid(NotificationWireCodec.deriveKey(
                    "FD-NOTIFICATION-INBOX-V1", message.tenantId(), message.messageId()));
            try {
                repository.insertProcessedInbox(message, inboxId, notificationId, now);
            }
            catch (DuplicateKeyException racedInsert) {
                effectiveDuplicate = repository.findDuplicate(message, true);
                if (effectiveDuplicate.kind() == NotificationRepository.DuplicateKind.CONFLICT) {
                    insertAuditOnce(message.tenantId(), auditId("FD-NOTIFICATION-CONFLICT-AUDIT-V1",
                                    message.tenantId(), message.messageId(), effectiveDuplicate.conflictCode()),
                            "NOTIFICATION_INBOX_CONFLICT", "MESSAGE_RECOVERY", null, "REJECTED",
                            "SERVICE", null, "Message identity/content conflict",
                            effectiveDuplicate.conflictCode(), message.correlationId(),
                            message.causationId(), now);
                    return NotificationConsumeResult.ack(effectiveDuplicate.conflictCode());
                }
                if (effectiveDuplicate.kind() == NotificationRepository.DuplicateKind.NONE) {
                    throw racedInsert;
                }
            }
        }
        if (effectiveDuplicate.kind() == NotificationRepository.DuplicateKind.RECOVERABLE) {
            completeRecoveredInboxOrAccept(message, effectiveDuplicate.inbox(), notificationId, now);
        }
        insertAuditOnce(message.tenantId(), auditId("FD-NOTIFICATION-PROCESSED-AUDIT-V1",
                        message.tenantId(), message.businessIntentKey()),
                "NOTIFICATION_PROCESSED", "NOTIFICATION", notificationId, "SUCCEEDED",
                "SERVICE", null, "Notification consumed", null, message.correlationId(),
                message.causationId(), now);
        return NotificationConsumeResult.ack("NOTIFICATION_PROCESSED");
    }

    /** Absorb a concurrent replay only after confirming its terminal content. */
    private void completeRecoveredInboxOrAccept(AcceptedNotification message,
                                                NotificationRepository.InboxRow row,
                                                UUID notificationId, Instant now) {
        int changed = repository.completeRecoveredInbox(message, row, notificationId, now);
        if (changed == 1) {
            resolveRecoveryStrict(message.tenantId(), recoveryIdFor(row), now);
            return;
        }
        NotificationRepository.InboxRow current = repository.findInbox(
                message.tenantId(), row.inboxId(), true).orElseThrow(
                () -> new IllegalStateException("Recovered Notification Inbox disappeared"));
        if (!"PROCESSED".equals(current.status())
                || !"NOTIFICATION".equals(current.resultType())
                || !notificationId.equals(current.resultId())) {
            throw new IllegalStateException("Recovered Notification Inbox race");
        }
        resolveRecoveryStrict(message.tenantId(), recoveryIdFor(current), now);
    }

    /**
     * Persists a deterministic payload/recipient rejection after the envelope
     * has crossed the trust boundary.  No Notification effect is created.
     */
    private NotificationConsumeResult rejectTrustedEnvelope(NotificationEnvelope envelope,
                                                             String code) {
        Instant now = now();
        NotificationRepository.Duplicate duplicate = repository.findDuplicate(envelope, true);
        if (duplicate.kind() == NotificationRepository.DuplicateKind.TERMINAL) {
            return NotificationConsumeResult.ack("NOTIFICATION_DUPLICATE");
        }
        if (duplicate.kind() == NotificationRepository.DuplicateKind.CONFLICT) {
            insertAuditOnce(envelope.tenantId(),
                    auditId("FD-NOTIFICATION-CONFLICT-AUDIT-V1", envelope.tenantId(),
                            envelope.messageId(), code),
                    "NOTIFICATION_INBOX_CONFLICT", "INBOX_MESSAGE",
                    duplicate.inbox() == null ? null : duplicate.inbox().inboxId(), "REJECTED",
                    "SERVICE", null, "Message identity/content conflict",
                    safeCode(duplicate.conflictCode()), envelope.correlationId(),
                    envelope.causationId(), now);
            return NotificationConsumeResult.ack(duplicate.conflictCode());
        }

        UUID inboxId = stableUuid(NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-INBOX-V1", envelope.tenantId(), envelope.messageId()));
        if (duplicate.kind() == NotificationRepository.DuplicateKind.RECOVERABLE) {
            NotificationRepository.InboxRow row = duplicate.inbox();
            if (repository.rejectRecoveredInbox(envelope, row, safeCode(code), now) != 1) {
                throw new IllegalStateException("Notification rejection recovery race");
            }
            repository.findRecoveryBySource(envelope.tenantId(), row.inboxId(), true)
                    .ifPresent(recovery -> resolveRecoveryStrict(
                            envelope.tenantId(), recovery.recoveryId(), now));
            inboxId = row.inboxId();
        }
        else {
            try {
                repository.insertRejectedInbox(envelope, inboxId, safeCode(code), now);
            }
            catch (DuplicateKeyException duplicateInsert) {
                NotificationRepository.Duplicate raced = repository.findDuplicate(envelope, true);
                if (raced.kind() == NotificationRepository.DuplicateKind.RECOVERABLE) {
                    if (repository.rejectRecoveredInbox(envelope, raced.inbox(), safeCode(code), now) != 1) {
                        throw new IllegalStateException("Notification rejection race");
                    }
                    inboxId = raced.inbox().inboxId();
                    repository.findRecoveryBySource(envelope.tenantId(), inboxId, true)
                            .ifPresent(recovery -> resolveRecoveryStrict(
                                    envelope.tenantId(), recovery.recoveryId(), now));
                }
                else if (raced.kind() == NotificationRepository.DuplicateKind.TERMINAL) {
                    return NotificationConsumeResult.ack("NOTIFICATION_DUPLICATE");
                }
                else if (raced.kind() == NotificationRepository.DuplicateKind.CONFLICT) {
                    return NotificationConsumeResult.ack(raced.conflictCode());
                }
                else {
                    throw duplicateInsert;
                }
            }
        }
        insertAuditOnce(envelope.tenantId(),
                auditId("FD-NOTIFICATION-REJECT-AUDIT-V1", envelope.tenantId(),
                        envelope.messageId(), safeCode(code)),
                "NOTIFICATION_REJECTED", "INBOX_MESSAGE", inboxId, "REJECTED",
                "SERVICE", null, "Notification payload rejected after envelope validation",
                safeCode(code), envelope.correlationId(), envelope.causationId(), now);
        return NotificationConsumeResult.ack(safeCode(code));
    }

    private static String safeCode(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_:.]{0,95}")) {
            return "NOTIFICATION_PAYLOAD_REJECTED";
        }
        return code;
    }

    private UUID recoveryIdFor(NotificationRepository.InboxRow inbox) {
        return repository.findRecoveryBySource(NotificationSupport.context().tenantId(),
                        inbox.inboxId(), true)
                .map(NotificationRepository.RecoveryRow::recoveryId)
                .orElseThrow(() -> new IllegalStateException(
                        "Notification recovery source has no recovery row"));
    }

    /** Resolve a source Recovery with an explicit one-row CAS. */
    private void resolveRecoveryStrict(UUID tenantId, UUID recoveryId, Instant at) {
        NotificationRepository.RecoveryRow row = repository.findRecovery(
                tenantId, recoveryId, true).orElseThrow(() ->
                new IllegalStateException("Notification recovery row is missing"));
        if ("RESOLVED".equals(row.state())) return;
        int changed = repository.resolveRecovery(tenantId, recoveryId,
                row.lockVersion(), at);
        if (changed != 1) {
            throw new IllegalStateException("Notification Recovery resolution CAS changed " + changed + " rows");
        }
    }

    private static boolean sameNotification(NotificationRepository.NotificationRow row,
                                             AcceptedNotification message) {
        return Objects.equals(row.recipientUserId(), message.recipientUserId())
                && Objects.equals(row.notificationKind(), message.notificationKind())
                && Objects.equals(row.sourceService(), message.binding().producerService())
                && Arrays.equals(row.sourceEffectKey(), message.sourceEffectKey())
                && Objects.equals(row.sourceRefType(), message.sourceRefType())
                && Objects.equals(row.sourceRefId(), message.sourceRefId())
                && Objects.equals(row.title(), message.title())
                && Objects.equals(row.body(), message.body());
    }

    private static UUID auditId(String namespace, Object... fields) {
        return stableUuid(NotificationWireCodec.deriveKey(namespace, fields));
    }

    /** Append-only audit anchors are idempotent and deterministic across retries. */
    private void insertAuditOnce(UUID tenantId, UUID auditId, String actionCode,
                                 String targetType, UUID targetId, String outcome,
                                 String actorType, UUID actorId, String reason,
                                 String errorCode, UUID correlationId, UUID causationId,
                                 Instant occurredAt) {
        Optional<NotificationRepository.AuditRow> existing = repository.findAudit(tenantId, auditId);
        if (existing.isPresent()) {
            if (!sameAudit(existing.get(), auditId, actionCode, targetType, targetId, outcome,
                    actorType, actorId, reason, errorCode, correlationId, causationId)) {
                throw new IllegalStateException("Notification audit identity/content conflict");
            }
            return;
        }
        try {
            repository.insertAudit(tenantId, auditId, actionCode, targetType, targetId, outcome,
                    actorType, actorId, reason, errorCode, correlationId, causationId, occurredAt);
        }
        catch (DuplicateKeyException duplicate) {
            NotificationRepository.AuditRow raced = repository.findAudit(tenantId, auditId)
                    .orElseThrow(() -> duplicate);
            if (!sameAudit(raced, auditId, actionCode, targetType, targetId, outcome,
                    actorType, actorId, reason, errorCode, correlationId, causationId)) {
                throw new IllegalStateException("Notification audit identity/content conflict", duplicate);
            }
        }
    }

    private static boolean sameAudit(NotificationRepository.AuditRow row, UUID auditId,
                                     String actionCode, String targetType, UUID targetId,
                                     String outcome, String actorType, UUID actorId,
                                     String reason, String errorCode, UUID correlationId,
                                     UUID causationId) {
        return Objects.equals(row.auditId(), auditId)
                && Objects.equals(row.actionCode(), actionCode)
                && Objects.equals(row.targetType(), targetType)
                && Objects.equals(row.targetId(), targetId)
                && Objects.equals(row.outcome(), outcome)
                && Objects.equals(row.actorType(), actorType)
                && Objects.equals(row.actorId(), actorId)
                && Objects.equals(row.reason(), reason)
                && Objects.equals(row.errorCode(), errorCode)
                && Objects.equals(row.correlationId(), correlationId)
                && Objects.equals(row.causationId(), causationId);
    }

    // ----- human API -------------------------------------------------------------

    public Page<NotificationDtos.NotificationResponse> list(String state, String kind,
                                                            Instant from, Instant to,
                                                            int pageSize, String pageToken,
                                                            String sort,
                                                            NotificationCursorTokenService cursors) {
        NotificationSupport.require(PermissionCode.NOTIFICATION_READ_SELF);
        if (state != null && !Set.of("UNREAD", "READ").contains(state)) {
            throw com.flowdesk.common.web.ApiException.badRequest("INVALID_QUERY_PARAMETER", "state 无效。");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw com.flowdesk.common.web.ApiException.badRequest("INVALID_QUERY_PARAMETER", "时间范围无效。");
        }
        String normalized = sort == null || sort.isBlank() ? "-createdAt" : sort;
        if (!Set.of("createdAt", "-createdAt").contains(normalized)) {
            throw com.flowdesk.common.web.ApiException.badRequest("SORT_UNSUPPORTED", "排序方式不受支持。");
        }
        boolean ascending = !normalized.startsWith("-");
        String scope = "notifications|" + state + "|" + kind + "|" + from + "|" + to;
        NotificationCursorTokenService.Cursor cursor = cursors.decode(pageToken,
                NotificationSupport.context().tenantId(), scope, pageSize, normalized);
        List<NotificationRepository.NotificationRow> rows = repository.listNotifications(
                NotificationSupport.context().tenantId(), state, kind, from, to, ascending,
                cursor == null ? null : cursor.value(), cursor == null ? null : cursor.id(), pageSize + 1);
        boolean more = rows.size() > pageSize;
        if (more) rows = rows.subList(0, pageSize);
        String next = null;
        if (more && !rows.isEmpty()) {
            var last = rows.get(rows.size() - 1);
            next = cursors.encode(NotificationSupport.context().tenantId(), scope, pageSize,
                    normalized, last.createdAt(), last.notificationId());
        }
        return new Page<>(rows.stream().map(NotificationService::response).toList(), next, normalized);
    }

    public NotificationDtos.UnreadCountResponse unreadCount() {
        NotificationSupport.require(PermissionCode.NOTIFICATION_READ_SELF);
        Instant now = now();
        return new NotificationDtos.UnreadCountResponse(repository.countUnread(
                NotificationSupport.context().tenantId(), NotificationSupport.actorId()), now);
    }

    public ReadStateResult updateReadState(UUID notificationId, NotificationDtos.ReadStateWrite request,
                                           String ifMatch, String idempotencyKey) {
        NotificationSupport.require(PermissionCode.NOTIFICATION_WRITE_SELF);
        String key = NotificationSupport.idempotency(idempotencyKey);
        if (request == null || request.state() == null) {
            throw com.flowdesk.common.web.ApiException.unprocessable("VALIDATION_FAILED", "state 必填。", List.of());
        }
        UUID tenant = NotificationSupport.context().tenantId();
        UUID actor = NotificationSupport.actorId();
        String canonical = "notificationId=" + notificationId + "|state=" + request.state().name();
        byte[] effectKey = NotificationWireCodec.deriveKey("FD-NOTIFICATION-READ-V1", tenant,
                actor, notificationId, key);
        UUID auditId = stableUuid(effectKey);
        Optional<NotificationRepository.AuditRow> prior = repository.findAudit(tenant, auditId);
        if (prior.isPresent()) {
            validateReadAudit(prior.get(), auditId, notificationId, actor, canonical);
            NotificationRepository.NotificationRow row = repository.findNotification(tenant, notificationId, false)
                    .filter(value -> actor.equals(value.recipientUserId())).orElseThrow(this::opaqueNotFound);
            return new ReadStateResult(response(row), true);
        }
        NotificationRepository.NotificationRow visible = repository.findNotification(tenant, notificationId, false)
                .filter(value -> actor.equals(value.recipientUserId())).orElseThrow(this::opaqueNotFound);
        long expected = NotificationSupport.ifMatch(ifMatch);
        if (visible.lockVersion() != expected) {
            throw new com.flowdesk.common.web.ApiException(HttpStatus.PRECONDITION_FAILED,
                    "RESOURCE_VERSION_CONFLICT", "资源已更新，请刷新后重试。", false, List.of(),
                    java.util.Map.of(org.springframework.http.HttpHeaders.ETAG,
                            "\"lv:" + visible.lockVersion() + "\""));
        }
        Instant now = now();
        ReadStateResult result = transactions.execute(tx -> {
            NotificationRepository.NotificationRow locked = repository.findNotification(tenant, notificationId, true)
                    .filter(value -> actor.equals(value.recipientUserId())).orElseThrow(this::opaqueNotFound);
            Optional<NotificationRepository.AuditRow> raced = repository.findAudit(tenant, auditId);
            if (raced.isPresent()) {
                validateReadAudit(raced.get(), auditId, notificationId, actor, canonical);
                return new ReadStateResult(response(locked), true);
            }
            if (locked.lockVersion() != expected) {
                throw new com.flowdesk.common.web.ApiException(HttpStatus.PRECONDITION_FAILED,
                        "RESOURCE_VERSION_CONFLICT", "资源已更新，请刷新后重试。", false, List.of(),
                        java.util.Map.of(org.springframework.http.HttpHeaders.ETAG,
                                "\"lv:" + locked.lockVersion() + "\""));
            }
            String target = request.state().name();
            if (!target.equals(locked.state())) {
                String newState = target;
                int changed = repository.updateReadState(tenant, notificationId, actor, expected,
                        newState, "READ".equals(newState) ? now : null, now);
                // A locked row plus an optimistic version must always produce
                // exactly one write when the requested state differs.  Never
                // turn a lost CAS into a successful idempotency audit.
                if (changed != 1) {
                    throw new IllegalStateException("Notification read-state CAS changed " + changed + " rows");
                }
                locked = repository.findNotification(tenant, notificationId, false).orElseThrow();
            }
            insertAuditOnce(tenant, auditId, "NOTIFICATION_READ_STATE", "NOTIFICATION",
                    notificationId, "SUCCEEDED", "USER", actor, canonical, null,
                    NotificationSupport.context().correlationId(), null, now);
            return new ReadStateResult(response(locked), false);
        });
        return Objects.requireNonNull(result);
    }

    private static void validateReadAudit(NotificationRepository.AuditRow audit, UUID auditId,
                                          UUID notificationId, UUID actor, String canonical) {
        if (!auditId.equals(audit.auditId()) || !"NOTIFICATION_READ_STATE".equals(audit.actionCode())
                || !"NOTIFICATION".equals(audit.targetType()) || !notificationId.equals(audit.targetId())
                || !"SUCCEEDED".equals(audit.outcome()) || !"USER".equals(audit.actorType())
                || !actor.equals(audit.actorId()) || !canonical.equals(audit.reason())) {
            throw com.flowdesk.common.web.ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "同一 Idempotency-Key 已用于不同业务意图。");
        }
    }

    private com.flowdesk.common.web.ApiException opaqueNotFound() {
        return com.flowdesk.common.web.ApiException.notFound("NOTIFICATION_RECIPIENT_ONLY", "通知不存在或不属于当前用户。");
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    private static UUID stableUuid(byte[] key) {
        byte[] first = Arrays.copyOf(key, 16);
        return ByteBufferHelper.uuid(first);
    }

    private static NotificationDtos.NotificationResponse response(NotificationRepository.NotificationRow row) {
        String domain = row.sourceService().contains("workflow") ? "WORKFLOW" : "TICKET";
        return new NotificationDtos.NotificationResponse(row.notificationId(), row.notificationKind(),
                domain, row.sourceRefType(), row.sourceRefId(), row.title(), row.body(), row.state(),
                row.readAt(), row.createdAt(), row.updatedAt(), row.lockVersion());
    }

    public record ReadStateResult(NotificationDtos.NotificationResponse notification, boolean replayed) { }
    public record Page<T>(List<T> items, String nextPageToken, String sort) { }

    private static final class ByteBufferHelper {
        static UUID uuid(byte[] value) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(value);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
    }
}
