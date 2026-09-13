package com.flowdesk.ticket.incident;

import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.ticket.service.IdempotencyCodec;
import com.flowdesk.ticket.sla.SlaIndexProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Alert -> Ticket M7 entry; all Ticket effects are one local transaction. */
public final class IncidentMessageConsumer {
    private static final Logger LOG=LoggerFactory.getLogger(IncidentMessageConsumer.class);
    private static final DateTimeFormatter MICROS=new DateTimeFormatterBuilder().appendInstant(6).toFormatter(Locale.ROOT);
    private static final DateTimeFormatter TICKET_DATE=DateTimeFormatter.ofPattern("yyyyMMdd",Locale.ROOT);
    private final IncidentEnvelopeGuard guard;private final IncidentInboxRepository repository;
    private final TransactionTemplate transactions;private final ObjectMapper json;private final Clock clock;
    private final SlaIndexProjection slaIndexProjection;
    IncidentMessageConsumer(IncidentEnvelopeGuard guard,IncidentInboxRepository repository,
                            PlatformTransactionManager tx,ObjectMapper json){this(guard,repository,tx,json,Clock.systemUTC(),null);}
    IncidentMessageConsumer(IncidentEnvelopeGuard guard,IncidentInboxRepository repository,
                            PlatformTransactionManager tx,ObjectMapper json,Clock clock){this(guard,repository,tx,json,clock,null);}
    IncidentMessageConsumer(IncidentEnvelopeGuard guard,IncidentInboxRepository repository,
                            PlatformTransactionManager tx,ObjectMapper json,Clock clock,
                            SlaIndexProjection slaIndexProjection){this.guard=guard;this.repository=repository;
        this.transactions=new TransactionTemplate(tx);this.json=json;this.clock=clock;this.slaIndexProjection=slaIndexProjection;}
    public IncidentConsumeResult consume(IncidentTransportMessage delivery){final AcceptedIncidentMessage message;
        try{message=guard.validateMain(delivery);}catch(IncidentContractViolation poison){
            LOG.warn("Incident delivery ACKed by transport guard; code={}",poison.code());return IncidentConsumeResult.ack(poison.code());}
        TenantContext context=TenantContext.ofValidatedBrokerMessage(message.tenantId(),IncidentMessageContract.ALERT_PRODUCER,message.correlationId());
        try(TenantContextScope ignored=TenantContextScope.open(context)){
            try{return Objects.requireNonNull(transactions.execute(tx->process(message)));}
            catch(DataAccessException|TransactionException|DependencyUnavailable|IllegalStateException transientFailure){
                LOG.warn("Incident delivery returned to Broker; dependency=TICKET_DATABASE");return IncidentConsumeResult.retry("TICKET_INCIDENT_DEPENDENCY_UNAVAILABLE");}}
    }
    private IncidentConsumeResult process(AcceptedIncidentMessage m){var duplicate=repository.duplicate(m,true);
        if(duplicate.kind()==IncidentInboxRepository.DuplicateKind.TERMINAL)return IncidentConsumeResult.ack("INCIDENT_MESSAGE_DUPLICATE");
        Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if(duplicate.kind()==IncidentInboxRepository.DuplicateKind.CONFLICT){repository.insertAudit(m,m.incidentId(),
                "INCIDENT_MESSAGE_CONFLICT","REJECTED","Message identity was reused.",duplicate.conflictCode(),now);
            return IncidentConsumeResult.ack(duplicate.conflictCode());}
        if(m.binding().kind()==IncidentMessageContract.Kind.CREATE_REQUESTED)return create(m,duplicate,now);
        return recover(m,duplicate,now);}
    private IncidentConsumeResult create(AcceptedIncidentMessage m,IncidentInboxRepository.Duplicate duplicate,Instant now){
        IncidentInboxRepository.Mapping existing=repository.mapping(m.tenantId(),m.incidentId());
        if(existing!=null){if(!"INCIDENT".equals(existing.originType())||!MessageDigest.isEqual(existing.creationIntent(),m.createTicketIntentKey())
                    ||!MessageDigest.isEqual(existing.ticketIntent(),m.createTicketIntentKey()))return reject(m,duplicate,"INCIDENT_MAPPING_CONFLICT",now);
            scheduleSlaIndexProjection(m.tenantId(),IncidentInboxRepository.slaClockId(
                    m.tenantId(),existing.ticketId(),"FIRST_RESPONSE"));
            scheduleSlaIndexProjection(m.tenantId(),IncidentInboxRepository.slaClockId(
                    m.tenantId(),existing.ticketId(),"RESOLUTION"));
            completeInbox(m,duplicate,existing.ticketId(),now);return IncidentConsumeResult.ack("INCIDENT_TICKET_ALREADY_ESTABLISHED");}
        IncidentInboxRepository.Policy policy=repository.incidentPolicy(m.tenantId());
        if(policy==null||policy.firstResponseSeconds()<=0||policy.resolutionSeconds()<=0
                ||invalidReminder(policy.firstReminderSeconds(),policy.firstResponseSeconds())
                ||invalidReminder(policy.resolutionReminderSeconds(),policy.resolutionSeconds()))throw new DependencyUnavailable();
        UUID ticket=stableUuid("FD-INCIDENT-TICKET-V1",m.tenantId(),m.incidentId(),m.createTicketIntentKey());
        LocalDate date=LocalDate.ofInstant(now,ZoneOffset.UTC);
        String ticketNo=String.format(Locale.ROOT,"FD-%s-%04d",TICKET_DATE.format(date),repository.nextSequence(m.tenantId(),date,now));
        repository.insertTicket(m,ticket,ticketNo,now);repository.insertMapping(m,ticket,now);
        repository.insertTimeline(m,ticket,m.createTicketIntentKey(),"INCIDENT_CREATED","监控 Incident 已创建工单。",now);
        UUID firstResponseClock=repository.insertClock(m,ticket,policy,"FIRST_RESPONSE",policy.firstResponseSeconds(),policy.firstReminderSeconds(),now);
        UUID resolutionClock=repository.insertClock(m,ticket,policy,"RESOLUTION",policy.resolutionSeconds(),policy.resolutionReminderSeconds(),now);
        scheduleSlaIndexProjection(m.tenantId(),firstResponseClock);
        scheduleSlaIndexProjection(m.tenantId(),resolutionClock);
        repository.insertAudit(m,ticket,"INCIDENT_TICKET_CREATED","SUCCEEDED",null,null,now);
        insertEstablishedOutbox(m,ticket,now);completeInbox(m,duplicate,ticket,now);
        return IncidentConsumeResult.ack("INCIDENT_TICKET_CREATED");}
    private IncidentConsumeResult recover(AcceptedIncidentMessage m,IncidentInboxRepository.Duplicate duplicate,Instant now){
        IncidentInboxRepository.Mapping mapping=repository.mapping(m.tenantId(),m.incidentId());
        if(mapping==null)return IncidentConsumeResult.retry("INCIDENT_MAPPING_NOT_ESTABLISHED");
        if(!"INCIDENT".equals(mapping.originType())||!MessageDigest.isEqual(mapping.creationIntent(),m.createTicketIntentKey())
                ||!MessageDigest.isEqual(mapping.ticketIntent(),m.createTicketIntentKey()))return reject(m,duplicate,"INCIDENT_MAPPING_CONFLICT",now);
        repository.insertTimeline(m,mapping.ticketId(),m.businessIntentKey(),"INCIDENT_RECOVERED",
                "监控 Incident 已恢复；工单主状态保持不变。",m.recoveredAt());
        repository.insertAudit(m,mapping.ticketId(),"INCIDENT_RECOVERY_TIMELINE_APPENDED","SUCCEEDED",null,null,now);
        completeInbox(m,duplicate,mapping.ticketId(),now);return IncidentConsumeResult.ack("INCIDENT_RECOVERY_APPENDED");}
    private IncidentConsumeResult reject(AcceptedIncidentMessage m,IncidentInboxRepository.Duplicate duplicate,String code,Instant now){
        if(duplicate.kind()==IncidentInboxRepository.DuplicateKind.RECOVERABLE)repository.rejectRecoveredInbox(m,duplicate.inbox(),code,now);
        else repository.insertRejectedInbox(m,inboxId(m),code,now);
        repository.insertAudit(m,m.incidentId(),"INCIDENT_MESSAGE_REJECTED","REJECTED","Incident references do not match Ticket facts.",code,now);
        return IncidentConsumeResult.ack(code);}
    private void completeInbox(AcceptedIncidentMessage m,IncidentInboxRepository.Duplicate duplicate,UUID ticket,Instant now){
        if(duplicate.kind()==IncidentInboxRepository.DuplicateKind.RECOVERABLE)repository.completeRecoveredInbox(m,duplicate.inbox(),ticket,now);
        else repository.insertProcessedInbox(m,inboxId(m),ticket,now);}
    private void scheduleSlaIndexProjection(UUID tenantId,UUID clockId){
        if(slaIndexProjection!=null)slaIndexProjection.schedule(tenantId,clockId);
    }
    private void insertEstablishedOutbox(AcceptedIncidentMessage m,UUID ticket,Instant now){byte[] intent=IdempotencyCodec.derivedKey(
            IncidentMessageContract.ESTABLISHED_INTENT_NAMESPACE,m.tenantId(),m.incidentId());LinkedHashMap<String,Object> body=new LinkedHashMap<>();
        body.put("schemaVersion","1");body.put("incidentId",m.incidentId().toString());body.put("createTicketIntentKey",HexFormat.of().formatHex(m.createTicketIntentKey()));
        body.put("ticketId",ticket.toString());body.put("establishedAt",MICROS.format(now));byte[] bytes;
        try{bytes=json.writeValueAsBytes(body);}catch(Exception impossible){throw new IllegalStateException("Unable to encode established result",impossible);}
        UUID message=stableUuid("FD-INCIDENT-ESTABLISHED-MESSAGE-V1",m.tenantId(),m.incidentId(),intent);
        UUID outbox=stableUuid("FD-INCIDENT-ESTABLISHED-OUTBOX-V1",m.tenantId(),m.incidentId(),intent);
        byte[] publication=IdempotencyCodec.publicationKey("FD-INCIDENT-ESTABLISHED-PUBLICATION-V1",m.tenantId(),message);
        repository.insertOutboxEstablished(m,ticket,outbox,message,publication,intent,bytes,sha(bytes),now);}
    private static UUID inboxId(AcceptedIncidentMessage m){return stableUuid("FD-INCIDENT-INBOX-V1",m.tenantId(),m.messageId());}
    private static UUID stableUuid(String namespace,Object... fields){return IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey(namespace,fields));}
    private static boolean invalidReminder(Long r,long d){return r!=null&&(r<0||r>d);}private static byte[] sha(byte[] b){
        try{return MessageDigest.getInstance("SHA-256").digest(b);}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static final class DependencyUnavailable extends RuntimeException{}
}
