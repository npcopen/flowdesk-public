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

/** Independent main-DLQ owner for both Alert -> Ticket contracts. */
public final class IncidentDlqAcceptor {
    private final IncidentEnvelopeGuard guard;private final IncidentInboxRepository repository;
    private final TransactionTemplate transactions;private final Clock clock=Clock.systemUTC();
    IncidentDlqAcceptor(IncidentEnvelopeGuard guard,IncidentInboxRepository repository,PlatformTransactionManager tx){
        this.guard=guard;this.repository=repository;this.transactions=new TransactionTemplate(tx);}
    public IncidentConsumeResult accept(IncidentTransportMessage delivery){final AcceptedIncidentMessage m;
        try{m=guard.validateDlq(delivery);}catch(IncidentContractViolation poison){return IncidentConsumeResult.ack(poison.code());}
        TenantContext context=TenantContext.ofValidatedBrokerMessage(m.tenantId(),IncidentMessageContract.ALERT_PRODUCER,m.correlationId());
        try(TenantContextScope ignored=TenantContextScope.open(context)){try{return Objects.requireNonNull(transactions.execute(tx->inside(m)));}
            catch(DataAccessException|TransactionException|IllegalStateException failure){return IncidentConsumeResult.retry("TICKET_DB_UNAVAILABLE");}}}
    private IncidentConsumeResult inside(AcceptedIncidentMessage m){var duplicate=repository.duplicate(m,true);
        if(duplicate.kind()==IncidentInboxRepository.DuplicateKind.TERMINAL||duplicate.kind()==IncidentInboxRepository.DuplicateKind.RECOVERABLE)
            return IncidentConsumeResult.ack("INCIDENT_DLQ_DUPLICATE");Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if(duplicate.kind()==IncidentInboxRepository.DuplicateKind.CONFLICT){repository.insertAudit(m,m.incidentId(),"INCIDENT_DLQ_CONFLICT","REJECTED",null,duplicate.conflictCode(),now);return IncidentConsumeResult.ack(duplicate.conflictCode());}
        UUID inbox=stableUuid("FD-INCIDENT-INBOX-V1",m.tenantId(),m.messageId());
        UUID recovery=stableUuid("FD-INCIDENT-RECOVERY-V1",m.tenantId(),inbox);
        repository.insertRecoveryInbox(m,inbox,"BROKER_RETRIES_EXHAUSTED",now);
        repository.insertRecovery(m,inbox,recovery,"BROKER_RETRIES_EXHAUSTED",now);
        repository.insertAudit(m,m.incidentId(),"INCIDENT_DLQ_ACCEPTED","FAILED","Controlled Inbox replay is required.","BROKER_RETRIES_EXHAUSTED",now);
        return IncidentConsumeResult.ack("INCIDENT_RECOVERY_REQUIRED");}
    private static UUID stableUuid(String namespace,Object... fields){return IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(namespace,fields));}
}
