package com.flowdesk.alert.event;

import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.tenant.TenantContextScope;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class AlertOutboxPublisher implements AutoCloseable {
    private static final DateTimeFormatter MICROS=new DateTimeFormatterBuilder().appendInstant(6).toFormatter(Locale.ROOT);
    private final AlertOutboxRepository repository;private final TransactionTemplate transactions;private final Clock clock;
    private final DefaultMQProducer createProducer,recoveryProducer;private final AtomicBoolean started=new AtomicBoolean();
    AlertOutboxPublisher(AlertOutboxRepository repository,PlatformTransactionManager tx,AlertOutboxProperties p){this(repository,tx,p,Clock.systemUTC());}
    AlertOutboxPublisher(AlertOutboxRepository r,PlatformTransactionManager tx,AlertOutboxProperties p,Clock c){repository=r;transactions=new TransactionTemplate(tx);clock=c;p.validate();
        var hook=new AclClientRPCHook(new SessionCredentials(p.getAccessKey(),p.getSecretKey()));
        createProducer=producer(AlertEventContract.TICKET_CREATE_REQUESTED,p,hook,"create");recoveryProducer=producer(AlertEventContract.INCIDENT_RECOVERED,p,hook,"recovery");}
    private static DefaultMQProducer producer(AlertEventContract.Binding b,AlertOutboxProperties p,AclClientRPCHook hook,String suffix){var producer=new DefaultMQProducer(b.producerGroup(),hook);
        producer.setNamesrvAddr(p.getNameServer().trim());producer.setInstanceName("flowdesk-alert-"+suffix+'-'+UUID.randomUUID());producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(Math.toIntExact(AlertEventContract.SEND_TIMEOUT.toMillis()));producer.setRetryTimesWhenSendFailed(0);producer.setRetryTimesWhenSendAsyncFailed(0);producer.setRetryAnotherBrokerWhenNotStoreOK(false);return producer;}
    public synchronized void start()throws Exception{if(started.get())return;try{createProducer.start();recoveryProducer.start();started.set(true);}catch(Exception e){createProducer.shutdown();recoveryProducer.shutdown();throw e;}}
    Run publishOnce(){if(TransactionSynchronizationManager.isActualTransactionActive()||TenantContextHolder.currentOrNull()!=null)throw new IllegalStateException("Alert publisher requires clean worker context");
        Instant scan=clock.instant();int scanned=0,published=0,recovered=0;for(var c:repository.candidates(scan,scan.minus(AlertEventContract.CLAIM_LEASE))){scanned++;
            TenantContext context=TenantContext.ofPersistedTask(c.tenantId(),ActorRef.service("flowdesk-alert-service"),c.correlationId());try(TenantContextScope ignored=TenantContextScope.open(context)){
                String token=UUID.randomUUID().toString();Instant claimedAt=clock.instant();var claimed=transactions.execute(s->c.attemptCount()==8?repository.claimRecovery(c,token,claimedAt,claimedAt.minus(AlertEventContract.CLAIM_LEASE)):repository.claim(c,token,claimedAt,claimedAt.minus(AlertEventContract.CLAIM_LEASE)));
                if(claimed==null||claimed.isEmpty())continue;var row=claimed.get();if(c.attemptCount()==8){if(Boolean.TRUE.equals(transactions.execute(s->repository.recover(row,clock.instant(),clock.instant().minus(AlertEventContract.CLAIM_LEASE),"BROKER_RESULT_UNKNOWN"))))recovered++;continue;}
                boolean ok=false;String code="BROKER_RESULT_UNKNOWN";try{ok=send(row);code=ok?null:"BROKER_SEND_NOT_CONFIRMED";}catch(IllegalArgumentException invalid){code="OUTBOX_CONTRACT_INVALID";}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(Exception ignoredFailure){}
                Instant done=clock.instant(),cutoff=done.minus(AlertEventContract.CLAIM_LEASE);if(ok){if(Boolean.TRUE.equals(transactions.execute(s->repository.published(row,done,cutoff))))published++;}
                else if(row.attemptCount()<8){String safe=code;var delay=AlertEventContract.FAILURE_BACKOFFS.get(row.attemptCount()-1);transactions.execute(s->repository.pending(row,done,cutoff,done.plus(delay),safe));}
                else{String safe=code;if(Boolean.TRUE.equals(transactions.execute(s->repository.recover(row,done,cutoff,safe))))recovered++;}}
        }return new Run(scanned,published,recovered);}
    private boolean send(AlertOutboxRepository.Claimed r)throws Exception{AlertEventContract.Binding b=binding(r.contractRef());byte[] body=r.body();if(body.length==0||body.length>AlertEventContract.MAX_BODY_BYTES||r.intent().length!=32||r.publicationKey().length!=32)throw new IllegalArgumentException("Invalid outbox keys/body");
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body));byte[] hash=MessageDigest.getInstance("SHA-256").digest(body);if(!MessageDigest.isEqual(hash,r.hash()))throw new IllegalArgumentException("Invalid body hash");
        LinkedHashMap<String,String> p=new LinkedHashMap<>();p.put("fd_envelope_version","1");p.put("fd_event_type",b.eventType());p.put("fd_contract_ref",b.contractRef());p.put("fd_contract_version","1");p.put("fd_producer",b.producer());p.put("fd_tenant_id",r.tenantId().toString());p.put("fd_message_id",r.messageId().toString());p.put("fd_business_intent_key",HexFormat.of().formatHex(r.intent()));p.put("fd_aggregate_type","INCIDENT");p.put("fd_aggregate_id",r.incidentId().toString());p.put("fd_correlation_id",r.correlationId().toString());if(r.causationId()!=null)p.put("fd_causation_id",r.causationId().toString());p.put("fd_occurred_at",MICROS.format(r.createdAt()));p.put("fd_body_sha256",HexFormat.of().formatHex(hash));
        Message m=new Message(b.topic(),body);m.setKeys(r.messageId().toString());m.setWaitStoreMsgOK(true);p.forEach(m::putUserProperty);DefaultMQProducer producer=b==AlertEventContract.TICKET_CREATE_REQUESTED?createProducer:recoveryProducer;SendResult result=producer.send(m,AlertEventContract.SEND_TIMEOUT.toMillis());return result!=null&&result.getSendStatus()==SendStatus.SEND_OK;}
    private static AlertEventContract.Binding binding(String ref){if(AlertEventContract.TICKET_CREATE_REQUESTED.contractRef().equals(ref))return AlertEventContract.TICKET_CREATE_REQUESTED;if(AlertEventContract.INCIDENT_RECOVERED.contractRef().equals(ref))return AlertEventContract.INCIDENT_RECOVERED;throw new IllegalArgumentException("Unexpected contract");}
    @Override public synchronized void close(){if(started.compareAndSet(true,false)){createProducer.shutdown();recoveryProducer.shutdown();}}
    record Run(int scanned,int published,int recovered){}
}
