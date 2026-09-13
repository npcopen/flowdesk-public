package com.flowdesk.alert.event;

import com.flowdesk.alert.repository.AlertRepository;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public final class TicketResultConsumer{
    public enum Result{ACK,RETRY}private final TicketResultEnvelopeGuard guard;private final TicketResultRepository inbox;private final AlertRepository alerts;private final TransactionTemplate transactions;private final Clock clock;
    TicketResultConsumer(TicketResultEnvelopeGuard g,TicketResultRepository i,AlertRepository a,PlatformTransactionManager tx){this(g,i,a,tx,Clock.systemUTC());}
    TicketResultConsumer(TicketResultEnvelopeGuard g,TicketResultRepository i,AlertRepository a,PlatformTransactionManager tx,Clock c){guard=g;inbox=i;alerts=a;transactions=new TransactionTemplate(tx);clock=c;}
    public Result consume(TicketResultTransportMessage delivery){final AcceptedTicketResult m;try{m=guard.validateMain(delivery);}catch(TicketResultContractViolation poison){return Result.ACK;}return execute(m);}
    Result replay(AcceptedTicketResult m){return execute(m);}private Result execute(AcceptedTicketResult m){TenantContext context=TenantContext.ofValidatedBrokerMessage(m.tenantId(),AlertEventContract.TICKET_ESTABLISHED.producer(),m.correlationId());try(TenantContextScope ignored=TenantContextScope.open(context)){try{return Objects.requireNonNull(transactions.execute(s->inside(m)));}catch(DataAccessException|IllegalStateException failure){return Result.RETRY;}}}
    private Result inside(AcceptedTicketResult m){var duplicate=inbox.duplicate(m,true);if(duplicate.kind()==TicketResultRepository.Kind.TERMINAL)return Result.ACK;Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if(duplicate.kind()==TicketResultRepository.Kind.CONFLICT){inbox.audit(m,"INCIDENT_TICKET_RESULT_CONFLICT","REJECTED",duplicate.code(),now);return Result.ACK;}
        var incident=alerts.findIncident(m.tenantId(),m.incidentId(),true).orElse(null);var link=alerts.findLink(m.tenantId(),m.incidentId(),true).orElse(null);String error=null;
        if(incident==null||link==null)error="INCIDENT_LINK_NOT_FOUND";else if(!MessageDigest.isEqual(link.createIntent(),m.createTicketIntentKey()))error="INCIDENT_CREATE_INTENT_CONFLICT";else if("ESTABLISHED".equals(link.status())&&!m.ticketId().equals(link.ticketId()))error="INCIDENT_TICKET_AUTHORITY_CONFLICT";
        if(error!=null){if(duplicate.kind()==TicketResultRepository.Kind.RECOVERABLE)inbox.rejectRecovered(m,duplicate.inbox(),error,now);else inbox.insertInbox(m,"REJECTED",null,1,error,now);inbox.audit(m,"INCIDENT_TICKET_RESULT_REJECTED","REJECTED",error,now);return Result.ACK;}
        inbox.establish(m,link,now);if(duplicate.kind()==TicketResultRepository.Kind.RECOVERABLE)inbox.finishRecovered(m,duplicate.inbox(),m.incidentId(),now);else inbox.insertInbox(m,"PROCESSED",m.incidentId(),1,null,now);inbox.audit(m,"INCIDENT_TICKET_LINK_ESTABLISHED","SUCCEEDED",null,now);return Result.ACK;}
}
