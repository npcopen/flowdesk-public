package com.flowdesk.ticket.recovery.impl;

import com.flowdesk.ticket.recovery.*;

import java.util.Optional;
import java.util.List;
import java.sql.Timestamp;
import com.flowdesk.ticket.persistence.Uuids;
import com.flowdesk.ticket.model.recovery.*;
import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.common.recovery.RecoveryReplayResumer;
import com.flowdesk.ticket.reverse.WorkflowReverseConsumeResult;
import com.flowdesk.ticket.reverse.WorkflowReverseConsumer;
import com.flowdesk.ticket.reverse.WorkflowReverseContract;
import com.flowdesk.ticket.reverse.WorkflowReverseTransportMessage;
import com.flowdesk.ticket.incident.IncidentConsumeResult;
import com.flowdesk.ticket.incident.IncidentMessageConsumer;
import com.flowdesk.ticket.incident.IncidentMessageContract;
import com.flowdesk.ticket.incident.IncidentTransportMessage;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 从数据库中的原始 Inbox 消息恢复投递并执行重放，以尝试关联标识和租约校验任务归属。 */
public final class TicketRecoveryReplayWorker implements TicketRecoveryReplayService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter MICROS = new DateTimeFormatterBuilder()
            .appendInstant(6).toFormatter();
    private final TaskExecutor executor;
    private final TicketRecoveryPersistenceMapper mapper;
    private final WorkflowReverseConsumer consumer;
    private final IncidentMessageConsumer incidentConsumer;
    private final TransactionTemplate transactions;

    /**
     * 构造消息恢复组件并保存调用依赖。
     */
    public TicketRecoveryReplayWorker(TaskExecutor executor, TicketRecoveryPersistenceMapper mapper,
                               WorkflowReverseConsumer consumer,
                               IncidentMessageConsumer incidentConsumer,
                               PlatformTransactionManager transactionManager) {
        this.executor = executor; this.mapper = mapper; this.consumer = consumer;
        this.incidentConsumer = incidentConsumer;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 构造消息恢复组件并保存调用依赖。
     */
    public TicketRecoveryReplayWorker(TaskExecutor executor, TicketRecoveryPersistenceMapper mapper,
                               WorkflowReverseConsumer consumer,
                               PlatformTransactionManager transactionManager) {
        this(executor, mapper, consumer, null, transactionManager);
    }

    /** 提交专用后台任务；执行器不可用时通过相同的受约束结算路径重新打开恢复记录。 */
    public void submit(TicketReplayWork work) {
        try { executor.execute(() -> execute(work)); }
        catch (RuntimeException unavailable) { settleFailure(work, "RECOVERY_REPLAY_WORKER_UNAVAILABLE"); }
    }

    /** 将持久续跑器重新领取的任务映射回同一重放入口，支持进程重启后的恢复。 */
    public void resume(RecoveryReplayResumer.Task task) {
        submit(new TicketReplayWork(task.tenantId(), task.recoveryId(),
                task.sourceRecordId(), task.actorId(), task.reason(), task.attemptCorrelationId(),
                task.sourceCorrelationId(), task.leaseUntil()));
    }

    /** 恢复持久任务的租户上下文，并校验尝试关联标识与租约快照后复用原消息消费入口。 */
    private void execute(TicketReplayWork work) {
        TenantContext task = TenantContext.ofPersistedTask(work.tenantId(),
                ActorRef.human(work.actorId()), work.attemptCorrelationId());
        try (TenantContextScope ignored = TenantContextScope.open(task)) {
            var recovery = find(work.tenantId(), work.recoveryId(), false).orElse(null);
            var source = inboxSource(work.tenantId(), work.sourceRecordId(), false).orElse(null);
            if (recovery == null || source == null || !"REPLAYING".equals(recovery.state())
                    || !work.attemptCorrelationId().equals(recovery.lastReplayCorrelationId())
                    || !work.leaseUntil().equals(recovery.nextAttemptAt())) return;
            if ("PROCESSED".equals(source.status()) || "REJECTED".equals(source.status())) {
                settleCompletion(work, null);
                return;
            }
            if (!"RECOVERY_REQUIRED".equals(source.status())) {
                settleFailure(work, "RECOVERY_REPLAY_NOT_TERMINAL");
                return;
            }
            if (isIncident(source.contractRef())) {
                if (incidentConsumer == null) throw new IllegalStateException("Incident replay entry is disabled");
                IncidentConsumeResult result = incidentConsumer.consume(restoreIncident(work.tenantId(), source));
                if (result.disposition() == IncidentConsumeResult.Disposition.RETRY) settleFailure(work, result.code());
                else settleCompletion(work, result.code());
            } else {
                if (consumer == null) throw new IllegalStateException("Workflow replay entry is disabled");
                WorkflowReverseConsumeResult result = consumer.consume(restore(work.tenantId(), source));
                if (result.disposition() == WorkflowReverseConsumeResult.Disposition.RETRY) settleFailure(work, result.code());
                else settleCompletion(work, result.code());
            }
        }
        catch (RuntimeException failure) { settleFailure(work, "RECOVERY_REPLAY_EXECUTION_FAILED"); }
    }

    /** 使用数据库原始载荷与原消息身份重建 Incident 信封，恢复执行仍经过原有严格契约校验。 */
    private IncidentTransportMessage restoreIncident(UUID tenantId,
                                                      com.flowdesk.ticket.model.recovery.InboxSource source) {
        IncidentMessageContract.Binding binding = switch (source.contractRef()) {
            case "flowdesk.incident.ticket-create-requested.v1" -> IncidentMessageContract.CREATE_REQUESTED;
            case "flowdesk.incident.recovered.v1" -> IncidentMessageContract.RECOVERED;
            default -> throw new IllegalStateException("Inactive Incident Inbox replay contract");
        };
        try {
            JsonNode payload = JSON.readTree(source.bodyBytes());
            UUID aggregate = UUID.fromString(payload.get("incidentId").stringValue());
            Map<String, String> p = new LinkedHashMap<>();
            p.put("fd_envelope_version", "1"); p.put("fd_event_type", binding.eventType());
            p.put("fd_contract_ref", binding.contractRef()); p.put("fd_contract_version", "1");
            p.put("fd_producer", IncidentMessageContract.ALERT_PRODUCER);
            p.put("fd_tenant_id", tenantId.toString()); p.put("fd_message_id", source.messageId().toString());
            p.put("fd_business_intent_key", HexFormat.of().formatHex(source.businessIntentKey()));
            p.put("fd_aggregate_type", IncidentMessageContract.AGGREGATE_TYPE);
            p.put("fd_aggregate_id", aggregate.toString()); p.put("fd_correlation_id", source.correlationId().toString());
            if (source.causationId() != null) p.put("fd_causation_id", source.causationId().toString());
            p.put("fd_occurred_at", MICROS.format(source.occurredAt()));
            p.put("fd_body_sha256", HexFormat.of().formatHex(source.bodySha256()));
            return new IncidentTransportMessage(binding.topic(),binding.consumerGroup(),source.messageId().toString(),null,source.bodyBytes(),p);
        } catch (Exception invalid) { throw new IllegalStateException("Persisted Incident replay source is invalid", invalid); }
    }

    /**
     * 判断恢复来源是否属于告警消息，选择对应的原始消费路径。
     */
    private static boolean isIncident(String contractRef) {
        return contractRef.startsWith("flowdesk.incident.");
    }

    /** 按原契约重建流程反向事件信封，保持消息身份、业务意图和因果元数据不变。 */
    private WorkflowReverseTransportMessage restore(UUID tenantId,
                                                     com.flowdesk.ticket.model.recovery.InboxSource source) {
        WorkflowReverseContract.Binding binding = switch (source.contractRef()) {
            case "flowdesk.workflow.instance-established.v1" -> WorkflowReverseContract.INSTANCE_ESTABLISHED;
            case "flowdesk.workflow.approval-finalized.v1" -> WorkflowReverseContract.APPROVAL_FINALIZED;
            default -> throw new IllegalStateException("Inactive Ticket Inbox replay contract");
        };
        try {
            JsonNode payload = JSON.readTree(source.bodyBytes());
            UUID aggregate = UUID.fromString(payload.get("workflowInstanceId").stringValue());
            Map<String, String> p = new LinkedHashMap<>();
            p.put("fd_envelope_version", "1"); p.put("fd_event_type", binding.eventType());
            p.put("fd_contract_ref", binding.contractRef()); p.put("fd_contract_version", "1");
            p.put("fd_producer", WorkflowReverseContract.PRODUCER);
            p.put("fd_tenant_id", tenantId.toString());
            p.put("fd_message_id", source.messageId().toString());
            p.put("fd_business_intent_key", HexFormat.of().formatHex(source.businessIntentKey()));
            p.put("fd_aggregate_type", WorkflowReverseContract.AGGREGATE_TYPE);
            p.put("fd_aggregate_id", aggregate.toString());
            p.put("fd_correlation_id", source.correlationId().toString());
            if (source.causationId() != null) p.put("fd_causation_id", source.causationId().toString());
            p.put("fd_occurred_at", MICROS.format(source.occurredAt()));
            p.put("fd_body_sha256", HexFormat.of().formatHex(source.bodySha256()));
            return new WorkflowReverseTransportMessage(binding.topic(), binding.consumerGroup(),
                    source.messageId().toString(), null, source.bodyBytes(), p);
        }
        catch (Exception invalid) { throw new IllegalStateException("Persisted replay source is invalid", invalid); }
    }

    /** 重新锁定恢复记录和来源，仅对匹配尝试及租约的任务结算，并追加一次确定性结果审计。 */
    private void settleCompletion(TicketReplayWork work, String code) {
        transactions.executeWithoutResult(tx -> {
            var recovery = find(work.tenantId(), work.recoveryId(), true).orElse(null);
            var source = inboxSource(work.tenantId(), work.sourceRecordId(), true).orElse(null);
            if (recovery == null || !work.attemptCorrelationId().equals(recovery.lastReplayCorrelationId())) return;
            if (source == null || !("PROCESSED".equals(source.status()) || "REJECTED".equals(source.status()))) {
                reopen(work, "RECOVERY_REPLAY_NOT_TERMINAL"); return;
            }
            if ("REPLAYING".equals(recovery.state()) && work.leaseUntil().equals(recovery.nextAttemptAt())) {
                resolveTerminal(work.tenantId(), work.recoveryId(), work.attemptCorrelationId(),
                        work.leaseUntil(), now());
            } else if (!"RESOLVED".equals(recovery.state())) return;
            String outcome = "PROCESSED".equals(source.status()) ? "SUCCEEDED" : "REJECTED";
            UUID auditId = TicketRecoveryIdempotency.outcomeAuditId(
                    work.tenantId(), work.attemptCorrelationId(), outcome);
            if (audit(work.tenantId(), auditId).isEmpty()) {
                insertAudit(work.tenantId(), auditId,
                        "TICKET_RECOVERY_REPLAY_COMPLETED", work.recoveryId(), outcome,
                        work.actorId(), work.reason(), "REJECTED".equals(outcome) ? code : null,
                        work.attemptCorrelationId(), work.sourceCorrelationId(), now());
            }
        });
    }

    /**
     * 按本次租约结算重放失败，阻止过期工作器影响新执行者。
     */
    private void settleFailure(TicketReplayWork work, String code) {
        transactions.executeWithoutResult(tx -> reopen(work, code));
    }

    /** 失败后以尝试关联标识和租约双重条件重新打开记录，防止过期线程覆盖新一轮执行。 */
    private void reopen(TicketReplayWork work, String code) {
        Instant now = now();
        if (reopen(work.tenantId(), work.recoveryId(),
                work.attemptCorrelationId(), work.leaseUntil(), code, now) == 1) {
            UUID auditId = TicketRecoveryIdempotency.outcomeAuditId(
                    work.tenantId(), work.attemptCorrelationId(), "FAILED");
            if (audit(work.tenantId(), auditId).isEmpty()) {
                insertAudit(work.tenantId(), auditId,
                        "TICKET_RECOVERY_REPLAY_COMPLETED", work.recoveryId(), "FAILED",
                        work.actorId(), work.reason(), code, work.attemptCorrelationId(),
                        work.sourceCorrelationId(), now);
            }
        }
    }
    /**
     * 取得当前时间并规范为业务使用的时间精度。
     */
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }

    /**
     * 按租户与业务标识读取记录，供后续状态校验和响应构造。
     */
    private Optional<Recovery> find(UUID tenantId, UUID recoveryId, boolean lock) {
        return mapper.findSelect(Uuids.toBytes(tenantId), Uuids.toBytes(recoveryId), lock).stream().findFirst();
    }

    /**
     * 读取恢复项关联 Inbox 原文，保证手工重放沿用持久化事实。
     */
    private Optional<InboxSource> inboxSource(UUID tenantId, UUID inboxId, boolean lock) {
        return mapper.inboxSourceSelect(Uuids.toBytes(tenantId), Uuids.toBytes(inboxId), lock).stream().findFirst();
    }

    /**
     * 记录恢复受理或后台结算审计，保留原始人工操作身份与本轮结果。
     */
    private Optional<Audit> audit(UUID tenantId, UUID auditId) {
        return mapper.auditSelect(Uuids.toBytes(tenantId), Uuids.toBytes(auditId)).stream().findFirst();
    }

    /**
     * 校验重开条件后恢复处理中，保留处理人并推进解决周期。
     */
    private int reopen(UUID tenantId, UUID recoveryId, UUID attemptCorrelation, Instant leaseUntil,
               String error, Instant now) {
        return mapper.reopenUpdate(error, Timestamp.from(now), Uuids.toBytes(tenantId), Uuids.toBytes(recoveryId), Uuids.toBytes(attemptCorrelation), Timestamp.from(leaseUntil));
    }

    /**
     * 对已终态来源完成恢复结算，避免再次消费已完成消息。
     */
    private int resolveTerminal(UUID tenantId, UUID recoveryId, UUID attemptCorrelation,
                        Instant leaseUntil, Instant now) {
        return mapper.resolveTerminalUpdate(Timestamp.from(now), Timestamp.from(now), Uuids.toBytes(tenantId), Uuids.toBytes(recoveryId), Uuids.toBytes(attemptCorrelation), Timestamp.from(leaseUntil));
    }

    /**
     * 保存业务操作审计，记录目标、主体、结果和请求链路。
     */
    private void insertAudit(UUID tenantId, UUID auditId, String action, UUID recoveryId,
                     String outcome, UUID actorId, String reason, String error,
                     UUID correlation, UUID causation, Instant now) {
        mapper.insertAuditUpdate(Uuids.toBytes(tenantId), Uuids.toBytes(auditId), action, Uuids.toBytes(recoveryId), outcome, Uuids.toBytes(actorId), reason, error, Uuids.toBytes(correlation), Uuids.toBytes(causation), Timestamp.from(now));
    }
}
