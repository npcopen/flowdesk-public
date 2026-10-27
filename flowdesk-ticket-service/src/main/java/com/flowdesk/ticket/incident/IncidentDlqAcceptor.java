package com.flowdesk.ticket.incident;

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

/** 独立接收 Alert 到 Ticket 两类消息的死信，将可验证的原消息持久化为恢复事实。 */
public final class IncidentDlqAcceptor {
    private final IncidentEnvelopeGuard guard;private final IncidentInboxService repository;
    private final TransactionTemplate transactions;private final Clock clock=Clock.systemUTC();
    /**
     * 构造告警建单组件并保存调用依赖。
     */
    IncidentDlqAcceptor(IncidentEnvelopeGuard guard,IncidentInboxService repository,PlatformTransactionManager tx){
        this.guard=guard;this.repository=repository;this.transactions=new TransactionTemplate(tx);}
    /**
     * 验证并接收告警建单死信，原子保存 Inbox、恢复项与审计。
     */
    public IncidentConsumeResult accept(IncidentTransportMessage delivery){final AcceptedIncidentMessage m;
        try{m=guard.validateDlq(delivery);}catch(IncidentContractViolation poison){return IncidentConsumeResult.ack(poison.code());}
        TenantContext context=TenantContext.ofValidatedBrokerMessage(m.tenantId(),IncidentMessageContract.ALERT_PRODUCER,m.correlationId());
        try(TenantContextScope ignored=TenantContextScope.open(context)){try{return Objects.requireNonNull(transactions.execute(tx->inside(m)));}
            catch(DataAccessException|TransactionException|IllegalStateException failure){return IncidentConsumeResult.retry("TICKET_DB_UNAVAILABLE");}}}
    /**
     * 在既定租户与事务内保存死信接收和恢复事实。
     */
    private IncidentConsumeResult inside(AcceptedIncidentMessage m){var duplicate=repository.duplicate(m,true);
        if(duplicate.kind()==com.flowdesk.ticket.model.incident.DuplicateKind.TERMINAL||duplicate.kind()==com.flowdesk.ticket.model.incident.DuplicateKind.RECOVERABLE)
            return IncidentConsumeResult.ack("INCIDENT_DLQ_DUPLICATE");Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if(duplicate.kind()==com.flowdesk.ticket.model.incident.DuplicateKind.CONFLICT){repository.insertAudit(m,m.incidentId(),"INCIDENT_DLQ_CONFLICT","REJECTED",null,duplicate.conflictCode(),now);return IncidentConsumeResult.ack(duplicate.conflictCode());}
        UUID inbox=stableUuid("FD-INCIDENT-INBOX-V1",m.tenantId(),m.messageId());
        UUID recovery=stableUuid("FD-INCIDENT-RECOVERY-V1",m.tenantId(),inbox);
        repository.insertRecoveryInbox(m,inbox,"BROKER_RETRIES_EXHAUSTED",now);
        repository.insertRecovery(m,inbox,recovery,"BROKER_RETRIES_EXHAUSTED",now);
        repository.insertAudit(m,m.incidentId(),"INCIDENT_DLQ_ACCEPTED","FAILED","Controlled Inbox replay is required.","BROKER_RETRIES_EXHAUSTED",now);
        return IncidentConsumeResult.ack("INCIDENT_RECOVERY_REQUIRED");}
    /**
     * 从规范业务键派生稳定 UUID，使重试与恢复复用同一业务身份。
     */
    private static UUID stableUuid(String namespace,Object... fields){return IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(namespace,fields));}
}
