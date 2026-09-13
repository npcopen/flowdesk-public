package com.flowdesk.ticket.recovery;

import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
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

final class TicketRecoveryReplayWorker {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter MICROS = new DateTimeFormatterBuilder()
            .appendInstant(6).toFormatter();
    private final TaskExecutor executor;
    private final TicketRecoveryRepository repository;
    private final WorkflowReverseConsumer consumer;
    private final IncidentMessageConsumer incidentConsumer;
    private final TransactionTemplate transactions;

    TicketRecoveryReplayWorker(TaskExecutor executor, TicketRecoveryRepository repository,
                               WorkflowReverseConsumer consumer,
                               IncidentMessageConsumer incidentConsumer,
                               PlatformTransactionManager transactionManager) {
        this.executor = executor; this.repository = repository; this.consumer = consumer;
        this.incidentConsumer = incidentConsumer;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    TicketRecoveryReplayWorker(TaskExecutor executor, TicketRecoveryRepository repository,
                               WorkflowReverseConsumer consumer,
                               PlatformTransactionManager transactionManager) {
        this(executor, repository, consumer, null, transactionManager);
    }

    void submit(TicketRecoveryService.ReplayWork work) {
        try { executor.execute(() -> execute(work)); }
        catch (RuntimeException unavailable) { settleFailure(work, "RECOVERY_REPLAY_WORKER_UNAVAILABLE"); }
    }

    private void execute(TicketRecoveryService.ReplayWork work) {
        TenantContext task = TenantContext.ofPersistedTask(work.tenantId(),
                ActorRef.human(work.actorId()), work.attemptCorrelationId());
        try (TenantContextScope ignored = TenantContextScope.open(task)) {
            var recovery = repository.find(work.tenantId(), work.recoveryId(), false).orElse(null);
            var source = repository.inboxSource(work.tenantId(), work.sourceRecordId(), false).orElse(null);
            if (recovery == null || source == null || !"REPLAYING".equals(recovery.state())
                    || !work.attemptCorrelationId().equals(recovery.lastReplayCorrelationId())
                    || !"RECOVERY_REQUIRED".equals(source.status())) return;
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

    private IncidentTransportMessage restoreIncident(UUID tenantId,
                                                      TicketRecoveryRepository.InboxSource source) {
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

    private static boolean isIncident(String contractRef) {
        return contractRef.startsWith("flowdesk.incident.");
    }

    private WorkflowReverseTransportMessage restore(UUID tenantId,
                                                     TicketRecoveryRepository.InboxSource source) {
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

    private void settleCompletion(TicketRecoveryService.ReplayWork work, String code) {
        transactions.executeWithoutResult(tx -> {
            var recovery = repository.find(work.tenantId(), work.recoveryId(), true).orElse(null);
            var source = repository.inboxSource(work.tenantId(), work.sourceRecordId(), true).orElse(null);
            if (recovery == null || source == null || !"RESOLVED".equals(recovery.state())
                    || !("PROCESSED".equals(source.status()) || "REJECTED".equals(source.status()))) {
                reopen(work, "RECOVERY_REPLAY_NOT_TERMINAL"); return;
            }
            String outcome = "PROCESSED".equals(source.status()) ? "SUCCEEDED" : "REJECTED";
            UUID auditId = TicketRecoveryIdempotency.outcomeAuditId(
                    work.tenantId(), work.attemptCorrelationId(), outcome);
            if (repository.audit(work.tenantId(), auditId).isEmpty()) {
                repository.insertAudit(work.tenantId(), auditId,
                        "TICKET_RECOVERY_REPLAY_COMPLETED", work.recoveryId(), outcome,
                        work.actorId(), work.reason(), "REJECTED".equals(outcome) ? code : null,
                        work.attemptCorrelationId(), work.sourceCorrelationId(), now());
            }
        });
    }

    private void settleFailure(TicketRecoveryService.ReplayWork work, String code) {
        transactions.executeWithoutResult(tx -> reopen(work, code));
    }

    private void reopen(TicketRecoveryService.ReplayWork work, String code) {
        Instant now = now();
        if (repository.reopen(work.tenantId(), work.recoveryId(),
                work.attemptCorrelationId(), code, now) == 1) {
            UUID auditId = TicketRecoveryIdempotency.outcomeAuditId(
                    work.tenantId(), work.attemptCorrelationId(), "FAILED");
            if (repository.audit(work.tenantId(), auditId).isEmpty()) {
                repository.insertAudit(work.tenantId(), auditId,
                        "TICKET_RECOVERY_REPLAY_COMPLETED", work.recoveryId(), "FAILED",
                        work.actorId(), work.reason(), code, work.attemptCorrelationId(),
                        work.sourceCorrelationId(), now);
            }
        }
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
}
