package com.flowdesk.ticket.reverse;

import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.ticket.service.IdempotencyCodec;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** 流程反向事件的独立死信接收器，将经过校验的原始字节落为一条工单 INBOX 恢复记录。 */
public final class WorkflowReverseDlqAcceptor {
    private static final String EXHAUSTED = "BROKER_RETRIES_EXHAUSTED";
    private final WorkflowReverseEnvelopeGuard guard;
    private final WorkflowReverseInboxService repository;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 构造工作流反向结果组件并保存调用依赖。
     */
    WorkflowReverseDlqAcceptor(WorkflowReverseEnvelopeGuard guard,
                               WorkflowReverseInboxService repository,
                               PlatformTransactionManager transactionManager) {
        this.guard = guard; this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = Clock.systemUTC();
    }

    /**
     * 验证并接收工作流反向结果死信，原子保存 Inbox、恢复项与审计。
     */
    public WorkflowReverseConsumeResult accept(WorkflowReverseTransportMessage delivery) {
        final AcceptedWorkflowReverse message;
        try { message = guard.validateDlq(delivery); }
        catch (WorkflowReverseContractViolation poison) {
            return WorkflowReverseConsumeResult.ack(poison.code());
        }
        TenantContext context = TenantContext.ofValidatedBrokerMessage(
                message.tenantId(), WorkflowReverseContract.PRODUCER, message.correlationId());
        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            try {
                return Objects.requireNonNull(transactions.execute(tx -> acceptInside(message)));
            }
            catch (DataAccessException | TransactionException | IllegalStateException failure) {
                return WorkflowReverseConsumeResult.retry("TICKET_DB_UNAVAILABLE");
            }
        }
    }

    /**
     * 在接收事务中原子保存死信 Inbox、恢复项与审计。
     */
    private WorkflowReverseConsumeResult acceptInside(AcceptedWorkflowReverse message) {
        var duplicate = repository.findDuplicate(message, true);
        if (duplicate.kind() == com.flowdesk.ticket.model.reverse.DuplicateKind.TERMINAL
                || duplicate.kind() == com.flowdesk.ticket.model.reverse.DuplicateKind.RECOVERABLE) {
            return WorkflowReverseConsumeResult.ack("WORKFLOW_REVERSE_DLQ_DUPLICATE");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (duplicate.kind() == com.flowdesk.ticket.model.reverse.DuplicateKind.CONFLICT) {
            repository.insertAudit(message, message.ticketId(), "WORKFLOW_REVERSE_DLQ_CONFLICT",
                    "REJECTED", "DLQ identity was reused with different content.",
                    duplicate.conflictCode(), now);
            return WorkflowReverseConsumeResult.ack(duplicate.conflictCode());
        }
        UUID inboxId = stableUuid("FD-WORKFLOW-REVERSE-INBOX-V1",
                message.tenantId(), message.messageId());
        UUID recoveryId = stableUuid("FD-WORKFLOW-REVERSE-RECOVERY-V1",
                message.tenantId(), inboxId);
        repository.insertRecoveryInbox(message, inboxId, EXHAUSTED, now);
        repository.insertRecovery(message, inboxId, recoveryId, EXHAUSTED,
                "Reverse message exhausted the bounded Ticket consumer retries.", now);
        repository.insertAudit(message, message.ticketId(), "WORKFLOW_REVERSE_DLQ_ACCEPTED",
                "FAILED", "Reverse message requires controlled Inbox replay.", EXHAUSTED, now);
        return WorkflowReverseConsumeResult.ack("WORKFLOW_REVERSE_RECOVERY_REQUIRED");
    }

    /**
     * 从规范业务键派生稳定 UUID，使重试与恢复复用同一业务身份。
     */
    private static UUID stableUuid(String namespace, Object... fields) {
        return IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(namespace, fields));
    }
}
