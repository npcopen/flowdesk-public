package com.flowdesk.notification.recovery.impl;
import com.flowdesk.notification.service.NotificationInboxService;
import com.flowdesk.notification.model.*;
import com.flowdesk.notification.recovery.*;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.notification.NotificationWireCodec;
import com.flowdesk.common.recovery.RecoveryReplayResumer;
import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.notification.dto.NotificationDtos;
import com.flowdesk.notification.event.NotificationConsumeResult;
import com.flowdesk.notification.event.NotificationConsumerService;
import com.flowdesk.notification.event.NotificationContract;
import com.flowdesk.notification.event.NotificationContractViolation;
import com.flowdesk.notification.event.NotificationEnvelopeGuard;
import com.flowdesk.notification.event.NotificationTransportMessage;
import com.flowdesk.notification.mapper.NotificationAuditMapper;
import com.flowdesk.notification.mapper.NotificationRecoveryMapper;
import com.flowdesk.notification.recovery.mapper.NotificationRecoverySourceMapper;
import com.flowdesk.notification.service.NotificationSupport;
import static com.flowdesk.notification.service.NotificationPersistenceSupport.*;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.Set;
import java.util.UUID;

import static com.flowdesk.notification.recovery.NotificationRecoveryResponseMapper.response;

/** 领取、执行并结算持久化人工重放，通过租约支持 HTTP 请求中断后的重启续跑。 */
@Service
public class NotificationRecoveryReplayServiceImpl implements NotificationRecoveryReplayService {
    private final NotificationRecoveryMapper repository;
    private final NotificationRecoverySourceMapper sources;
    private final NotificationAuditMapper audits;
    private final NotificationConsumerService consumer;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final NotificationEnvelopeGuard guard;

    /**
     * 注入恢复专用来源、恢复及审计映射和对应事务管理器；提交领取后由原消费服务重放，时钟和守卫负责租约及信封核验。
     */
    public NotificationRecoveryReplayServiceImpl(
            @Qualifier("notificationPrivilegedRecoveryMapper") NotificationRecoveryMapper repository,
            @Qualifier("notificationRecoverySourceMapper") NotificationRecoverySourceMapper sources,
            @Qualifier("notificationPrivilegedAuditMapper") NotificationAuditMapper audits,
            NotificationConsumerService consumer,
            @Qualifier("notificationRecoveryTransactionManager") PlatformTransactionManager transactionManager,
            Clock clock, NotificationEnvelopeGuard guard) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.audits = Objects.requireNonNull(audits, "audits");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    /**
     * 校验恢复权限、资源版本和幂等意图，提交重放租约后使用原信封走相同消费入口。
     */
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
        NotificationRecoveryRow current = repository.findRecovery(tenant, recoveryId, false).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("RECOVERY_ROUTE_MISMATCH", "Recovery 不存在或不属于该路由。"));
        if (!"INBOX".equals(current.direction())) {
            throw ApiException.notFound("RECOVERY_ROUTE_MISMATCH", "Recovery 路由不匹配。");
        }
        UUID replayAuditId = stableUuid(NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-RECOVERY-V1", tenant, recoveryId, key));

        // 领取状态、原消息验证和重放审计一并提交，因此相同命令键的后续请求仅返回已记录的结果。
        ReplayClaim claim;
        try {
            claim = transactions.execute(tx -> {
                NotificationRecoveryRow locked = repository.findRecovery(tenant, recoveryId, true).stream().findFirst()
                        .orElseThrow(() -> ApiException.notFound("RECOVERY_ROUTE_MISMATCH", "Recovery 不存在或不属于该路由。"));
                Optional<NotificationAuditRow> priorAudit = audits.findAudit(tenant, replayAuditId).stream().findFirst();
                if (priorAudit.isPresent()) {
                    // 首次请求可能已经将 Inbox 结算为 PROCESSED 或 REJECTED；幂等重放只读取结果，不重新驱动该终态消息。
                    ReplayAuditOutcome outcome = validateReplayAudit(priorAudit.get(), replayAuditId,
                            recoveryId, actor, command.reason(), sources.findSource(tenant, locked.sourceRecordId(), false).stream().findFirst()
                                    .map(NotificationInboxRow::correlationId).orElse(null));
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
                NotificationInboxRow source = sources.findSource(tenant, locked.sourceRecordId(), true).stream().findFirst()
                        .orElseThrow(() -> new NonReplayableSourceException("REPLAY_SOURCE_MISSING"));
                NotificationTransportMessage delivery = toDelivery(source);
                UUID attemptCorrelation = NotificationSupport.context().correlationId();
                Instant claimedAt = now();
                if (repository.claimReplay(RecoveryReplayResumer.leaseUntil(claimedAt), claimedAt, actor, command.reason(), attemptCorrelation, tenant, recoveryId, locked.lockVersion()) != 1) {
                    throw new ApiException(org.springframework.http.HttpStatus.PRECONDITION_FAILED,
                            "RESOURCE_VERSION_CONFLICT", "Recovery 已被其他操作领取。", false);
                }
                insertAuditOnce(tenant, replayAuditId, "NOTIFICATION_RECOVERY_REPLAY_CLAIMED",
                        "MESSAGE_RECOVERY", recoveryId, "SUCCEEDED", "USER", actor,
                        command.reason(), null, attemptCorrelation, source.correlationId(), claimedAt);
                return new ReplayClaim(repository.findRecovery(tenant, recoveryId, false).stream().findFirst().orElseThrow(),
                        delivery, false);
            });
        }
        catch (NonReplayableSourceException invalidSource) {
            // 先校验原消息再提交领取事务；不完整的旧数据在新事务中记录不可重放结论，避免永久重试。
            markNonReplayable(tenant, recoveryId, invalidSource.code(), replayAuditId,
                    actor, command.reason());
            throw ApiException.conflict("RECOVERY_NOT_REPLAYABLE",
                    "原始 Inbox 源事实不完整，不能安全重放。");
        }
        if (claim == null || claim.idempotent() || !"REPLAYING".equals(claim.recovery().state())) {
            return response(claim == null ? current : claim.recovery());
        }

        var row = claim.recovery();
        resume(new RecoveryReplayResumer.Task(tenant, recoveryId, row.sourceRecordId(), actor,
                command.reason(), row.lastReplayCorrelationId(), null, row.nextAttemptAt()));
        return getWithoutPermission(recoveryId);
    }

    /** HTTP 领取或进程重启后均执行同一条持久化重放路径。 */
    public void resume(RecoveryReplayResumer.Task task) {
        TenantContext context = TenantContext.ofPersistedTask(task.tenantId(),
                ActorRef.human(task.actorId()), task.attemptCorrelationId());
        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            NotificationTransportMessage delivery = transactions.execute(tx -> {
                var recovery = repository.findRecovery(task.tenantId(), task.recoveryId(), true).stream().findFirst().orElse(null);
                if (!ownsLease(recovery, task)) return null;
                var source = sources.findSource(task.tenantId(), task.sourceRecordId(), true).stream().findFirst().orElse(null);
                if (source != null && Set.of("PROCESSED", "REJECTED").contains(source.status())) {
                    resolveAfterReplay(task.tenantId(), task.recoveryId(), now());
                    return null;
                }
                return toDelivery(source);
            });
            if (delivery == null) return;
            NotificationConsumeResult result = consumer.consume(delivery);
            if (result == null || result.disposition() == NotificationConsumeResult.Disposition.RETRY) {
                String code = result == null || result.code() == null
                        ? "REPLAY_CONSUME_RETRY" : safeCode(result.code());
                reopenAfterReplay(task, code, "Notification replay returned retry");
            } else {
                transactions.execute(tx -> {
                    var row = repository.findRecovery(task.tenantId(), task.recoveryId(), true).stream().findFirst().orElse(null);
                    return ownsLease(row, task)
                            ? resolveAfterReplay(task.tenantId(), task.recoveryId(), now()) : 0;
                });
            }
        } catch (RuntimeException unavailable) {
            try (TenantContextScope ignored = TenantContextScope.open(context)) {
                reopenAfterReplay(task, "REPLAY_CONSUME_UNAVAILABLE", "Notification replay consumer unavailable");
            }
        }
    }

    /** 租约需同时匹配原消息、尝试关联及截止时间，避免过期工作线程覆盖接管任务的状态。 */
    private static boolean ownsLease(NotificationRecoveryRow row,
                                     RecoveryReplayResumer.Task task) {
        return row != null && "REPLAYING".equals(row.state())
                && task.sourceRecordId().equals(row.sourceRecordId())
                && task.attemptCorrelationId().equals(row.lastReplayCorrelationId())
                && task.leaseUntil().equals(row.nextAttemptAt());
    }

    /**
     * 在入口已授权的内部路径读取恢复结果，供异步结算返回当前视图。
     */
    private NotificationDtos.MessageRecoveryResponse getWithoutPermission(UUID id) {
        return repository.findRecovery(NotificationSupport.context().tenantId(), id, false).stream().findFirst()
                .map(NotificationRecoveryResponseMapper::response).orElseThrow();
    }

    /** 先核对不可变原消息完整性、路由和摘要，再重建同一消息供正常消费者处理。 */
    private NotificationTransportMessage toDelivery(NotificationInboxRow inbox) {
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
            // 领取恢复记录前重新执行固定消息契约校验。可信载荷拒绝可重放为终态 REJECTED；未越过信任边界的源数据则不能安全重建。
            guard.validateMain(delivery);
        }
        catch (NotificationContractViolation invalid) {
            if (!invalid.hasTrustedEnvelope()) {
                throw new NonReplayableSourceException("REPLAY_SOURCE_ENVELOPE_INVALID");
            }
        }
        return delivery;
    }

    /**
     * 根据冻结契约定位原消息绑定，未知契约不得进入重放路径。
     */
    private static com.flowdesk.common.notification.NotificationEventContract.Binding bindingFor(
            NotificationInboxRow inbox) {
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

    /**
     * 重放未完成时重新开放恢复记录，保留错误供后续人工处理。
     */
    private void reopenAfterReplay(RecoveryReplayResumer.Task task, String code, String summary) {
        transactions.execute(tx -> {
            NotificationRecoveryRow row = repository.findRecovery(task.tenantId(), task.recoveryId(), true).stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("Notification Recovery row is missing"));
            if (!ownsLease(row, task)) return null;
            int changed = repository.reopenRecovery(safeCode(code), summary, now(), task.tenantId(), task.recoveryId(), row.lockVersion());
            if (changed != 1) {
                throw new IllegalStateException("Notification Recovery reopen race");
            }
            return null;
        });
    }

    /** 恢复完成以本地 Inbox 终态为依据；ACK 却未产生终态时重新开放记录，保留后续处理机会。 */
    private Integer resolveAfterReplay(UUID tenant, UUID recoveryId, Instant at) {
        NotificationRecoveryRow row = repository.findRecovery(tenant, recoveryId, true).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Notification Recovery row is missing"));
        if ("RESOLVED".equals(row.state())) return 0;
        int changed = repository.resolveIfSourceTerminal(at, tenant, recoveryId, row.lockVersion());
        if (changed == 1) return changed;
        // ACK 本身不证明 Inbox 已进入终态；若消费未产生终态事实，则重新开放恢复记录，供后续人工重试。
        int reopened = repository.reopenRecovery("REPLAY_ACK_SOURCE_NOT_TERMINAL", "Replay ACK did not produce a terminal Notification Inbox source.", at, tenant, recoveryId, row.lockVersion());
        if (reopened != 1) {
            throw new IllegalStateException("Notification Recovery resolve/reopen race");
        }
        return 0;
    }

    /** 写入确定性重放审计，重复时核验全部不可变内容。 */
    private void insertAuditOnce(UUID tenantId, UUID auditId, String actionCode,
                                 String targetType, UUID targetId, String outcome,
                                 String actorType, UUID actorId, String reason,
                                 String errorCode, UUID correlationId, UUID causationId,
                                 Instant occurredAt) {
        requireTenant(tenantId);
        requireTransaction();
        Optional<NotificationAuditRow> existing = audits.findAudit(tenantId, auditId).stream().findFirst();
        if (existing.isPresent()) {
            validateAudit(existing.get(), auditId, actionCode, targetType, targetId, outcome,
                    actorType, actorId, reason, errorCode, correlationId, causationId);
            return;
        }
        try {
            audits.insertAudit(tenantId, auditId, actionCode, targetType, targetId, outcome,
                    actorType, actorId, reason, errorCode, correlationId, causationId, occurredAt);
        }
        catch (DuplicateKeyException duplicate) {
            NotificationAuditRow raced = audits.findAudit(tenantId, auditId).stream().findFirst()
                    .orElseThrow(() -> duplicate);
            validateAudit(raced, auditId, actionCode, targetType, targetId, outcome,
                    actorType, actorId, reason, errorCode, correlationId, causationId);
        }
    }

    /**
     * 比较既有审计稳定身份及内容，拒绝将冲突记录当作成功重放。
     */
    private static void validateAudit(NotificationAuditRow row, UUID auditId,
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

    /**
     * 关闭缺少合法原始事实的重放路径，记录原因以终止不安全恢复。
     */
    private void markNonReplayable(UUID tenant, UUID recoveryId, String sourceCode,
                                   UUID replayAuditId, UUID actor, String replayReason) {
        String code = "NON_REPLAYABLE";
        String summary = "Persisted Notification Inbox source failed immutable replay validation.";
        transactions.executeWithoutResult(tx -> {
            NotificationRecoveryRow row = repository.findRecovery(tenant, recoveryId, true).stream().findFirst()
                    .orElseThrow(() -> ApiException.notFound("RECOVERY_ROUTE_MISMATCH",
                            "Recovery 不存在或不属于该路由。"));
            UUID sourceCorrelation = sources.findSource(tenant, row.sourceRecordId(), false).stream().findFirst()
                    .map(NotificationInboxRow::correlationId).orElse(null);
            if ("RESOLVED".equals(row.state())) {
                audits.findAudit(tenant, replayAuditId).stream().findFirst().ifPresent(audit ->
                        validateReplayAudit(audit, replayAuditId, recoveryId, actor,
                                replayReason, sourceCorrelation));
                return;
            }
            Instant at = now();
            if (repository.markNonReplayable(at, code, sourceCode == null ? summary : summary + " (" + safeCode(sourceCode) + ")", tenant, recoveryId, row.lockVersion()) != 1) {
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

    /**
     * 限制错误码格式和长度，避免异常文本污染恢复持久化字段。
     */
    private static String safeCode(String code) {
        return code != null && code.matches("[A-Z][A-Z0-9_:.]{0,95}")
                ? code : "REPLAY_CONSUME_RETRY";
    }

    /**
     * 从稳定摘要生成 UUID，防止重复处理同一业务意图时创建新的记录身份。
     */
    private static UUID stableUuid(byte[] key) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(
                java.util.Arrays.copyOf(key, 16));
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /**
     * 核验重放审计与恢复记录、操作者及内容匹配，避免重试关联错误历史。
     */
    private static ReplayAuditOutcome validateReplayAudit(NotificationAuditRow row,
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

    /**
     * 通知恢复已提交的领取结果，供事务外消费及结算核验本次处理权。
     */
    private record ReplayClaim(NotificationRecoveryRow recovery,
                               NotificationTransportMessage delivery,
                               boolean idempotent) { }

    /**
     * 重放结果审计分类，用于记录本次恢复尝试的明确结算状态。
     */
    private enum ReplayAuditOutcome { CLAIMED, NON_REPLAYABLE }

    /**
     * 原来源不足以安全重放的确定性异常，携带稳定错误码供关闭恢复路径。
     */
    private static final class NonReplayableSourceException extends RuntimeException {
        /**
         * 创建异常或专用实现并保留传入原因，供调用方分类处理。
         */
        private NonReplayableSourceException(String code) { super(code); }
        /**
         * 取得稳定错误码，供调用方分类处理并保存有限长度错误信息。
         */
        private String code() { return getMessage(); }
    }

    /**
     * 读取注入时钟并截断至数据库可保存的精度，便于持久化比较及测试。
     */
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
