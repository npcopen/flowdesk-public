package com.flowdesk.ticket.incident;

import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.tenant.TenantContextScope;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

final class IncidentResultPublisher {
    private final IncidentResultOutboxRepository repository;
    private final IncidentResultRocketMqTransport transport;
    private final TransactionTemplate transactions;
    private final Clock clock;
    IncidentResultPublisher(IncidentResultOutboxRepository repository,
                            IncidentResultRocketMqTransport transport,
                            PlatformTransactionManager tx) {
        this(repository, transport, tx, Clock.systemUTC());
    }
    IncidentResultPublisher(IncidentResultOutboxRepository repository,
                            IncidentResultRocketMqTransport transport,
                            PlatformTransactionManager tx, Clock clock) {
        this.repository=repository; this.transport=transport; this.transactions=new TransactionTemplate(tx); this.clock=clock;
    }
    Run publishOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive() || TenantContextHolder.currentOrNull()!=null)
            throw new IllegalStateException("Incident result publisher requires a clean worker context");
        Instant scan=clock.instant(); int sent=0, recovered=0, scanned=0;
        for (var candidate:repository.candidates(scan, scan.minus(IncidentMessageContract.OUTBOX_CLAIM_LEASE))) {
            scanned++;
            TenantContext context=TenantContext.ofPersistedTask(candidate.tenantId(),
                    ActorRef.service(IncidentMessageContract.TICKET_PRODUCER),candidate.correlationId());
            try(TenantContextScope ignored=TenantContextScope.open(context)) {
                String token=UUID.randomUUID().toString(); Instant claimedAt=clock.instant();
                var claimed=transactions.execute(s->candidate.attemptCount()==IncidentMessageContract.OUTBOX_MAX_ATTEMPTS
                        ?repository.claimRecovery(candidate,token,claimedAt,claimedAt.minus(IncidentMessageContract.OUTBOX_CLAIM_LEASE))
                        :repository.claim(candidate,token,claimedAt,claimedAt.minus(IncidentMessageContract.OUTBOX_CLAIM_LEASE)));
                if (claimed==null||claimed.isEmpty()) continue;
                var row=claimed.get();
                if(candidate.attemptCount()==IncidentMessageContract.OUTBOX_MAX_ATTEMPTS) {
                    if(Boolean.TRUE.equals(transactions.execute(s->repository.recover(row,clock.instant(),
                            clock.instant().minus(IncidentMessageContract.OUTBOX_CLAIM_LEASE),"BROKER_RESULT_UNKNOWN")))) recovered++;
                    continue;
                }
                boolean ok=false; String code="BROKER_RESULT_UNKNOWN";
                try { ok=transport.send(IncidentResultEnvelopeFactory.create(row)); code=ok?null:"BROKER_SEND_NOT_CONFIRMED"; }
                catch(IllegalArgumentException invalid){code="OUTBOX_CONTRACT_INVALID";}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                catch(Exception ignoredFailure){/* never persist or log credential-bearing exception text */}
                Instant done=clock.instant(); Instant cutoff=done.minus(IncidentMessageContract.OUTBOX_CLAIM_LEASE);
                String failureCode = code;
                if(ok) { if(Boolean.TRUE.equals(transactions.execute(s->repository.published(row,done,cutoff)))) sent++; }
                else if(row.attemptCount()<IncidentMessageContract.OUTBOX_MAX_ATTEMPTS) {
                    String error=Objects.requireNonNull(code); var delay=IncidentMessageContract.FAILURE_BACKOFFS.get(row.attemptCount()-1);
                    transactions.execute(s->repository.pending(row,done,cutoff,done.plus(delay),error));
                } else if(Boolean.TRUE.equals(transactions.execute(s->repository.recover(row,done,cutoff,Objects.requireNonNull(failureCode))))) recovered++;
            }
        }
        return new Run(scanned,sent,recovered);
    }
    record Run(int scanned,int published,int recovered) { }
}
