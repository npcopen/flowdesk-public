package com.flowdesk.alert.service.impl;
import com.flowdesk.alert.model.LinkRow;
import com.flowdesk.alert.model.IncidentCycleRow;
import com.flowdesk.alert.model.EventRow;

import com.flowdesk.alert.service.*;

import com.flowdesk.alert.dto.AlertDtos;
import com.flowdesk.alert.event.AlertEventContract;
import com.flowdesk.alert.mapper.*;
import com.flowdesk.alert.webhook.VerifiedWebhook;
import com.flowdesk.alert.webhook.WebhookAlertPayload;
import com.flowdesk.alert.webhook.WebhookProperties;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.common.web.AsyncProgress;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Webhook 的业务接纳入口：把一次外部告警转换为本地事件、故障周期及待发送消息。
 *
 * <p>调用方已经完成验签和报文解析，本类继续校验 nonce 与 eventId 是否被使用，
 * 再根据 fingerprint（故障指纹）和 cycleRef（外部故障周期）决定创建、聚合或恢复故障。
 * 同一指纹最多保留一个活动故障；无法归入当前周期的事件也会留下可追溯的接纳事实。
 *
 * <p>nonce、事件、故障、工单关联、Outbox 和审计使用同一本地事务。
 * 本类只保存发消息的意图，实际发送由 AlertOutboxPublisher 在事务提交后完成。
 * 阅读主流程可从 accept → acceptOnce → decide 开始。
 */
public class WebhookAcceptanceServiceImpl implements WebhookAcceptanceService {
    private static final DateTimeFormatter MICROS =
            new DateTimeFormatterBuilder().appendInstant(6).toFormatter(Locale.ROOT);
    private static final int MAX_INCIDENT_RACE_RETRIES = 12;
    private final AlertEventMapper events;
    private final IncidentMapper incidents;
    private final AlertMessageMapper messages;
    private final AlertAuditMapper audits;
    private final TransactionTemplate transactions;
    private final WebhookProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    /**
     * 组装当前组件依赖并委托完整构造方法；使用 UTC 系统时钟等默认运行配置，显式依赖版本可用于确定性测试。
     */
    public WebhookAcceptanceServiceImpl(
            AlertEventMapper events, IncidentMapper incidents, AlertMessageMapper messages, AlertAuditMapper audits,
            PlatformTransactionManager transactionManager,
            WebhookProperties properties,
            ObjectMapper json) {
        this(events, incidents, messages, audits, transactionManager, properties, json, Clock.systemUTC());
    }

    /**
     * 注入事件、故障、消息及审计持久化能力，建立接纳事务模板，并保留 Webhook 配置、序列化器和可替换时钟。
     */
    WebhookAcceptanceServiceImpl(
            AlertEventMapper events, IncidentMapper incidents, AlertMessageMapper messages, AlertAuditMapper audits,
            PlatformTransactionManager transactionManager,
            WebhookProperties properties,
            ObjectMapper json,
            Clock clock) {
        this.events = events; this.incidents = incidents; this.messages = messages; this.audits = audits;
        this.transactions = new TransactionTemplate(transactionManager);
        // 连接池统一使用 READ_COMMITTED，使新事务能看到已提交的并发胜出结果，避免每次接纳都额外切换并重置隔离级别。
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    /**
     * 接纳一次请求；遇到故障创建或更新竞争时，回滚整次尝试并开启新事务重读。
     *
     * <p>不能在失败事务里继续重试，否则此前写入的 nonce、事件或过时快照可能影响裁决。
     * eventId 冲突有单独语义：读取已提交事件，区分相同内容的重复提交与不同内容的标识冲突。
     */
    public AlertDtos.WebhookReceipt accept(VerifiedWebhook verified, WebhookAlertPayload payload) {
        for (int attempt = 0; attempt < MAX_INCIDENT_RACE_RETRIES; attempt++) {
            try {
                return Objects.requireNonNull(
                        transactions.execute(tx -> acceptOnce(verified, payload)));
            } catch (IncidentRace retry) {
                // 失败事务已回滚 nonce 和事件；新事务重新读取权威故障记录并将本次告警聚合进去。
                concurrencyBackoff(attempt);
            } catch (ConcurrencyFailureException retry) {
                // 并发创建首代故障时，活动指纹唯一约束可能触发数据库并发失败。整个本地事务已回滚，可按同一逻辑请求重试，由已提交故障裁决。
                concurrencyBackoff(attempt);
            } catch (EventRace race) {
                EventRow existing = awaitEvent(verified, payload);
                throw eventConflict(existing, verified, payload);
            }
        }
        throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "INCIDENT_CONCURRENCY_RETRY_EXHAUSTED",
                "Incident 并发裁决暂时不可用。",
                true);
    }

    /**
     * 完成一次事务尝试：占用 nonce → 判断周期 → 保存事件 → 建立业务租户上下文 → 应用副作用。
     * 任一步失败都会由外层事务回滚；返回的 async 只说明后续工作已登记，不代表工单已建立。
     */
    private AlertDtos.WebhookReceipt acceptOnce(
            VerifiedWebhook verified, WebhookAlertPayload payload) {
        Instant receivedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        // nonce 防止同一签名请求重放；eventId 则防止外部业务事件重复，两者是独立约束。
        try {
            events.insertNoncePreTrust(
                    verified.tenantId(),
                    UUID.randomUUID(),
                    verified.integrationId(),
                    AlertIdempotencyCodec.sha256(
                            verified.nonce().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    verified.requestTimestamp(),
                    receivedAt,
                    receivedAt.plus(properties.getTimestampWindow()));
        } catch (DuplicateKeyException replay) {
            throw ApiException.conflict("WEBHOOK_NONCE_REPLAYED", "Webhook nonce 已被使用。");
        }
        // 先通过数据库现有周期裁决动作；事件唯一键确认后才建立业务租户上下文并更新后续事实。
        Decision decision = decide(verified, payload);
        UUID alertEventId = UUID.randomUUID();
        try {
            events.insertEventPreTrust(
                    verified.tenantId(),
                    alertEventId,
                    verified.integrationId(),
                    payload.eventId(),
                    payload.eventType(),
                    payload.fingerprint(),
                    payload.cycleRef(),
                    payload.occurredAt(),
                    receivedAt,
                    decision.disposition,
                    decision.incidentId,
                    verified.rawBodySha256(),
                    payload.summary(),
                    verified.correlationId());
        } catch (DuplicateKeyException collision) {
            throw new EventRace();
        }

        TenantContext trusted =
                TenantContext.ofWebhook(
                        verified.tenantId(), verified.integrationId(), verified.correlationId());
        List<AsyncProgress> async = new ArrayList<>();
        try (TenantContextScope ignored = TenantContextScope.open(trusted)) {
            if (decision.action == Action.RECORD_RECOVERY) {
                // RESOLVED 先于 FIRING 到达：直接记录已恢复周期，阻止迟到的触发事件再建工单。
                var legacy = decision.legacyRecovery;
                try {
                    incidents.insertRecoveredIncident(
                            verified.tenantId(),
                            decision.incidentId,
                            payload.fingerprint(),
                            decision.generation,
                            legacy == null ? alertEventId : legacy.alertEventId(),
                            legacy == null ? receivedAt : legacy.receivedAt(),
                            legacy == null ? payload.summary() : legacy.summary(),
                            legacy == null ? verified.integrationId() : legacy.integrationId());
                } catch (DuplicateKeyException collision) {
                    throw new IncidentRace();
                }
                audits.insertAudit(
                        verified.tenantId(),
                        UUID.randomUUID(),
                        "INCIDENT_RECOVERY_RECORDED",
                        "INCIDENT",
                        decision.incidentId,
                        "SUCCEEDED",
                        "RECOVERY_BEFORE_FIRING",
                        null,
                        "INTEGRATION",
                        verified.integrationId(),
                        verified.correlationId(),
                        null,
                        receivedAt);
            } else if (decision.action == Action.CREATE) {
                try {
                    incidents.insertIncident(
                            verified.tenantId(),
                            decision.incidentId,
                            payload.fingerprint(),
                            decision.generation,
                            alertEventId,
                            receivedAt,
                            payload.summary(),
                            verified.integrationId());
                } catch (DuplicateKeyException collision) {
                    throw new IncidentRace();
                }
                byte[] createIntent =
                        AlertIdempotencyCodec.derivedKey(
                                AlertEventContract.TICKET_CREATE_REQUESTED.intentNamespace(),
                                verified.tenantId(),
                                decision.incidentId);
                // 建单意图、故障关联和 Outbox 与故障一起提交，防止故障已建立但异步建单请求丢失。
                messages.insertTicketLink(
                        verified.tenantId(), decision.incidentId, createIntent, receivedAt);
                insertCreateOutbox(verified, payload, decision, createIntent, receivedAt);
                audits.insertAudit(
                        verified.tenantId(),
                        UUID.randomUUID(),
                        "INCIDENT_OPENED",
                        "INCIDENT",
                        decision.incidentId,
                        "SUCCEEDED",
                        null,
                        null,
                        "INTEGRATION",
                        verified.integrationId(),
                        verified.correlationId(),
                        null,
                        receivedAt);
                async.add(new AsyncProgress("INCIDENT_TICKET", "REQUESTED", decision.incidentId));
            } else if (decision.action == Action.AGGREGATE) {
                // 当前周期的重复触发只增加事件数、更新摘要，不再生成新的建单意图。
                requireOne(
                        incidents.aggregateFiring(
                                verified.tenantId(),
                                decision.active,
                                alertEventId,
                                receivedAt,
                                payload.summary(),
                                verified.integrationId()));
            } else if (decision.action == Action.RECOVER) {
                // 恢复故障后登记时间线消息；关联工单的实际更新仍由异步消息完成。
                requireOne(
                        incidents.recoverIncident(
                                verified.tenantId(),
                                decision.active,
                                alertEventId,
                                receivedAt,
                                payload.summary(),
                                verified.integrationId()));
                LinkRow link =
                        messages.findLink(verified.tenantId(), decision.incidentId, true)
                                .orElseThrow();
                insertRecoveredOutbox(
                        verified,
                        decision.incidentId,
                        alertEventId,
                        link.createIntent(),
                        receivedAt);
                audits.insertAudit(
                        verified.tenantId(),
                        UUID.randomUUID(),
                        "INCIDENT_RECOVERED",
                        "INCIDENT",
                        decision.incidentId,
                        "SUCCEEDED",
                        null,
                        null,
                        "INTEGRATION",
                        verified.integrationId(),
                        verified.correlationId(),
                        null,
                        receivedAt);
                async.add(
                        new AsyncProgress(
                                "INCIDENT_RECOVERY_TIMELINE", "PENDING", decision.incidentId));
                reconcileWaitingCycles(
                        verified.tenantId(),
                        payload.fingerprint(),
                        payload.occurredAt(),
                        receivedAt);
            }
            audits.insertAudit(
                    verified.tenantId(),
                    UUID.randomUUID(),
                    "ALERT_EVENT_ACCEPTED",
                    "ALERT_EVENT",
                    alertEventId,
                    "SUCCEEDED",
                    decision.disposition,
                    null,
                    "INTEGRATION",
                    verified.integrationId(),
                    verified.correlationId(),
                    null,
                    receivedAt);
        }
        String status = decision.action == Action.RECOVER ? "RECOVERED" : decision.incidentStatus;
        return new AlertDtos.WebhookReceipt(
                alertEventId,
                payload.eventId(),
                decision.disposition,
                decision.incidentId,
                status,
                HexFormat.of().formatHex(verified.rawBodySha256()),
                receivedAt,
                List.copyOf(async));
    }

    /**
     * 只根据已有事实决定动作，此时尚未写入本次事件，也尚未建立业务租户上下文。
     *
     * <p>同一活动周期收到 FIRING 时聚合，收到 RESOLVED 时恢复；命中历史周期则记录为迟到事件。
     * 若已有活动故障而新事件属于未知周期，先标记 REVIEW_REQUIRED，避免直接覆盖当前故障。
     * 没有活动故障且没有历史周期时，才创建新故障或记录“恢复先到”的已恢复周期。
     */
    private Decision decide(VerifiedWebhook verified, WebhookAlertPayload payload) {
        var active =
                incidents.findActiveForUpdatePreTrust(verified.tenantId(), payload.fingerprint());
        if (active.isPresent()) {
            IncidentCycleRow row = active.get();
            if (Objects.equals(row.cycleRef(), payload.cycleRef())) {
                return new Decision(
                        "APPLIED",
                        row.incidentId(),
                        row.status(),
                        0,
                        row,
                        "FIRING".equals(payload.eventType()) ? Action.AGGREGATE : Action.RECOVER);
            }
            return historical(verified, payload);
        }
        List<IncidentCycleRow> history =
                incidents.findByCyclePreTrust(
                        verified.tenantId(), payload.fingerprint(), payload.cycleRef());
        if (history.size() == 1) {
            var old = history.get(0);
            return new Decision(
                    "LATE_OLD_GENERATION", old.incidentId(), old.status(), 0, null, Action.NONE);
        }
        if (!history.isEmpty()) {
            return new Decision("REVIEW_REQUIRED", null, null, 0, null, Action.NONE);
        }
        // 兼容旧版本将先到的恢复消息保存为未关联事实的情况，补建故障时保留原始审计记录。
        var legacyRecovery =
                events.findUnlinkedRecoveryPreTrust(
                        verified.tenantId(), payload.fingerprint(), payload.cycleRef());
        if (legacyRecovery.isPresent()) {
            return new Decision(
                    "LATE_OLD_GENERATION",
                    UUID.randomUUID(),
                    "RECOVERED",
                    incidents.nextGenerationPreTrust(verified.tenantId(), payload.fingerprint()),
                    null,
                    Action.RECORD_RECOVERY,
                    legacyRecovery.get());
        }
        // 恢复消息先到时直接保存已恢复故障，避免后来的同周期触发消息错误地创建新工单。
        if ("RESOLVED".equals(payload.eventType())) {
            return new Decision(
                    "APPLIED",
                    UUID.randomUUID(),
                    "RECOVERED",
                    incidents.nextGenerationPreTrust(verified.tenantId(), payload.fingerprint()),
                    null,
                    Action.RECORD_RECOVERY);
        }
        return new Decision(
                "APPLIED",
                UUID.randomUUID(),
                "ACTIVE",
                incidents.nextGenerationPreTrust(verified.tenantId(), payload.fingerprint()),
                null,
                Action.CREATE);
    }

    /** 活动周期不匹配时，只接受唯一历史归属；没有归属或出现歧义时保留为待复核事件。 */
    private Decision historical(VerifiedWebhook verified, WebhookAlertPayload payload) {
        List<IncidentCycleRow> rows =
                incidents.findByCyclePreTrust(
                        verified.tenantId(), payload.fingerprint(), payload.cycleRef());
        if (rows.size() == 1) {
            var old = rows.get(0);
            return new Decision(
                    "LATE_OLD_GENERATION", old.incidentId(), old.status(), 0, null, Action.NONE);
        }
        return new Decision("REVIEW_REQUIRED", null, null, 0, null, Action.NONE);
    }

    /**
     * 定时补偿入口，调用方需先建立持久化任务的租户上下文。
     * 扫描结果可能已经过时，因此在事务内再次检查有无活动故障，再推进等待周期。
     */
    public void reconcilePersistedCycles(UUID tenantId, String fingerprint, Instant recoveredAt) {
        transactions.executeWithoutResult(
                tx -> {
                    if (incidents.findActiveForUpdatePreTrust(tenantId, fingerprint).isEmpty())
                        reconcileWaitingCycles(
                                tenantId,
                                fingerprint,
                                recoveredAt,
                                clock.instant().truncatedTo(ChronoUnit.MICROS));
                });
    }

    /**
     * 从已接纳、尚未归属故障的 FIRING 事实补建后续周期，不重新接纳外部请求。
     *
     * <p>已有对应恢复事实的周期直接补为 RECOVERED；否则补建 ACTIVE 故障和建单 Outbox。
     * 补建 ACTIVE 周期前若发现已有活动故障，则停止推进；已有恢复事实的周期仍可补为 RECOVERED。
     * 该方法既用于恢复事务内的即时推进，也用于后台补偿。
     */
    private void reconcileWaitingCycles(
            UUID tenantId, String fingerprint, Instant recoveredAt, Instant now) {
        for (var event : events.findWaitingCycles(tenantId, fingerprint, recoveredAt)) {
            if (!incidents.findByCyclePreTrust(tenantId, fingerprint, event.cycleRef()).isEmpty())
                continue;
            UUID id = UUID.randomUUID();
            int generation = incidents.nextGenerationPreTrust(tenantId, fingerprint);
            var recovery =
                    events.findUnlinkedRecoveryPreTrust(
                            tenantId, fingerprint, event.cycleRef());
            if (recovery.isPresent()) {
                var recovered = recovery.get();
                incidents.insertRecoveredIncident(
                        tenantId,
                        id,
                        fingerprint,
                        generation,
                        recovered.alertEventId(),
                        now,
                        recovered.summary(),
                        recovered.integrationId());
            } else {
                if (incidents.findActiveForUpdatePreTrust(tenantId, fingerprint).isPresent())
                    return;
                try {
                    incidents.insertIncident(
                            tenantId,
                            id,
                            fingerprint,
                            generation,
                            event.alertEventId(),
                            now,
                            event.summary(),
                            event.integrationId());
                } catch (DuplicateKeyException race) {
                    throw new IncidentRace();
                }
                byte[] intent =
                        AlertIdempotencyCodec.derivedKey(
                                AlertEventContract.TICKET_CREATE_REQUESTED.intentNamespace(),
                                tenantId,
                                id);
                messages.insertTicketLink(tenantId, id, intent, now);
                // 原事件已经验签并持久化接纳，此处只补建后续业务事实，不重新接纳 Webhook 或 nonce。
                var source =
                        new VerifiedWebhook(
                                tenantId,
                                event.integrationId(),
                                event.integrationCode(),
                                event.integrationName(),
                                now.toString(),
                                now,
                                "",
                                event.correlationId(),
                                new byte[0],
                                event.rawBodySha256());
                var payload =
                        new WebhookAlertPayload(
                                event.externalEventId(),
                                event.eventType(),
                                fingerprint,
                                event.cycleRef(),
                                event.occurredAt(),
                                event.summary());
                insertCreateOutbox(
                        source,
                        payload,
                        new Decision("APPLIED", id, "ACTIVE", generation, null, Action.CREATE),
                        intent,
                        now);
            }
            audits.insertAudit(
                    tenantId,
                    UUID.randomUUID(),
                    "ALERT_CYCLE_RECONCILED",
                    "INCIDENT",
                    id,
                    "SUCCEEDED",
                    "已处理因前一轮故障尚未恢复而等待的告警。",
                    null,
                    "INTEGRATION",
                    event.integrationId(),
                    event.correlationId(),
                    null,
                    now);
        }
    }

    /** 保存建单契约报文；同一故障的建单意图键固定，供工单服务和结果回执关联。 */
    private void insertCreateOutbox(
            VerifiedWebhook verified,
            WebhookAlertPayload payload,
            Decision decision,
            byte[] intent,
            Instant now) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "1");
        body.put("incidentId", decision.incidentId.toString());
        body.put("createTicketIntentKey", AlertIdempotencyCodec.hex(intent));
        body.put("fingerprint", payload.fingerprint());
        body.put("generationNo", decision.generation);
        body.put("cycleRef", payload.cycleRef());
        body.put("summary", payload.summary());
        body.put("openedAt", MICROS.format(now));
        insertOutbox(
                verified,
                decision.incidentId,
                AlertEventContract.TICKET_CREATE_REQUESTED,
                intent,
                json(body),
                null,
                now,
                "INTEGRATION");
    }

    /** 恢复消息携带原建单意图键，使工单服务即使乱序收到消息也能识别同一故障。 */
    private void insertRecoveredOutbox(
            VerifiedWebhook verified,
            UUID incidentId,
            UUID recoveryEventId,
            byte[] createIntent,
            Instant now) {
        byte[] intent =
                AlertIdempotencyCodec.derivedKey(
                        AlertEventContract.INCIDENT_RECOVERED.intentNamespace(),
                        verified.tenantId(),
                        incidentId);
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "1");
        body.put("incidentId", incidentId.toString());
        body.put("createTicketIntentKey", AlertIdempotencyCodec.hex(createIntent));
        body.put("recoveryEventId", recoveryEventId.toString());
        body.put("recoveredAt", MICROS.format(now));
        insertOutbox(
                verified,
                incidentId,
                AlertEventContract.INCIDENT_RECOVERED,
                intent,
                json(body),
                null,
                now,
                "INTEGRATION");
    }

    /**
     * 固化待发消息的契约字节、摘要和消息身份，之后的重试复用此记录；这里不保存 Webhook 请求原文。
     * businessIntent 标识“要做哪件业务事”，publicationKey 标识“发送哪一条消息”。
     */
    private void insertOutbox(
            VerifiedWebhook verified,
            UUID incidentId,
            AlertEventContract.Binding binding,
            byte[] intent,
            byte[] body,
            UUID causation,
            Instant now,
            String creator) {
        UUID messageId = UUID.randomUUID();
        messages.insertOutbox(
                verified.tenantId(),
                UUID.randomUUID(),
                messageId,
                AlertIdempotencyCodec.publicationKey(verified.tenantId(), messageId),
                intent,
                incidentId,
                binding.contractRef(),
                body,
                AlertIdempotencyCodec.sha256(body),
                verified.correlationId(),
                causation,
                now,
                creator);
    }

    /**
     * 按约定序列化消息载荷；序列化失败中止当前业务处理，避免持久化无法可靠发送的内容。
     */
    private byte[] json(LinkedHashMap<String, Object> value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (Exception impossible) {
            throw new IllegalStateException("Unable to encode M7 payload", impossible);
        }
    }

    /** 唯一键冲突后短暂重读胜出事务；若仍不可见，返回可重试错误，避免猜测重复结果。 */
    private EventRow awaitEvent(
            VerifiedWebhook verified, WebhookAlertPayload payload) {
        for (int i = 0; i < 20; i++) {
            var row =
                    events.findEventPreTrust(
                            verified.tenantId(), verified.integrationId(), payload.eventId());
            if (row.isPresent()) return row.get();
            Thread.onSpinWait();
        }
        throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "WEBHOOK_EVENT_RACE_UNKNOWN",
                "Webhook eventId 并发结果暂不可读。",
                true);
    }

    /** 业务字段和原始报文摘要都相同才是重复；复用 eventId 改写内容属于冲突。 */
    private static ApiException eventConflict(
            EventRow existing,
            VerifiedWebhook verified,
            WebhookAlertPayload payload) {
        boolean same =
                existing.eventType().equals(payload.eventType())
                        && existing.fingerprint().equals(payload.fingerprint())
                        && existing.cycleRef().equals(payload.cycleRef())
                        && existing.occurredAt().equals(payload.occurredAt())
                        && Objects.equals(existing.summary(), payload.summary())
                        && MessageDigest.isEqual(
                                existing.rawBodySha256(), verified.rawBodySha256());
        return ApiException.conflict(
                same ? "WEBHOOK_EVENT_DUPLICATE" : "WEBHOOK_EVENT_CONFLICT",
                "Webhook eventId 已存在。");
    }

    /** 乐观更新未命中说明读取的故障事实已变化，交由最外层重开事务，而非忽略更新。 */
    private static void requireOne(int value) {
        if (value != 1) throw new IncidentRace();
    }

    /**
     * 在故障周期竞争后短暂退避再重试，线程被中断时恢复中断标记并停止当前接纳尝试。
     */
    private static void concurrencyBackoff(int attempt) {
        long millis = Math.min(50L, 1L << Math.min(attempt, 5));
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "INCIDENT_CONCURRENCY_INTERRUPTED",
                    "Incident 并发裁决已中断。",
                    true);
        }
    }

    /**
     * Webhook 周期裁决的内部动作，区分创建、聚合、恢复及只记录恢复事实，驱动同事务副作用。
     */
    private enum Action {
        CREATE,
        AGGREGATE,
        RECOVER,
        RECORD_RECOVERY,
        NONE
    }

    /** disposition 是事件接纳结果，action 是要执行的副作用；二者不能互相替代。 */
    private record Decision(
            String disposition,
            UUID incidentId,
            String incidentStatus,
            int generation,
            IncidentCycleRow active,
            Action action,
            EventRow legacyRecovery) {
        /**
         * 为没有历史恢复事实的周期裁决构造决策，复用完整记录构造方法并将旧恢复事件设为 null。
         */
        Decision(
                String disposition,
                UUID incidentId,
                String incidentStatus,
                int generation,
                IncidentCycleRow active,
                Action action) {
            this(disposition, incidentId, incidentStatus, generation, active, action, null);
        }
    }

    /**
     * 故障周期创建或版本更新失去竞争的内部信号；外层接纳流程回滚当前事务后重新读取权威事实。
     */
    private static final class IncidentRace extends RuntimeException {}

    /**
     * 告警事件唯一键竞争的内部信号；外层回读已提交事件，区分同内容重试与事件标识冲突。
     */
    private static final class EventRace extends RuntimeException {}
}
