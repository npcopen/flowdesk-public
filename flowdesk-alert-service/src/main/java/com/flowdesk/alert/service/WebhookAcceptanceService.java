package com.flowdesk.alert.service;

import com.flowdesk.alert.api.AlertDtos;
import com.flowdesk.alert.event.AlertEventContract;
import com.flowdesk.alert.repository.AlertRepository;
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

/** API-049 local transaction, including DB-authoritative Incident concurrency. */
public final class WebhookAcceptanceService {
    private static final DateTimeFormatter MICROS = new DateTimeFormatterBuilder()
            .appendInstant(6).toFormatter(Locale.ROOT);
    private static final int MAX_INCIDENT_RACE_RETRIES = 12;
    private final AlertRepository repository;
    private final TransactionTemplate transactions;
    private final WebhookProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    public WebhookAcceptanceService(AlertRepository repository,
                                    PlatformTransactionManager transactionManager,
                                    WebhookProperties properties, ObjectMapper json) {
        this(repository, transactionManager, properties, json, Clock.systemUTC());
    }
    WebhookAcceptanceService(AlertRepository repository,
                             PlatformTransactionManager transactionManager,
                             WebhookProperties properties, ObjectMapper json, Clock clock) {
        this.repository=repository;this.transactions=new TransactionTemplate(transactionManager);
        // The Alert Hikari pool fixes every connection at READ_COMMITTED. Keeping the
        // isolation at the connection boundary preserves fresh retry visibility without
        // issuing a query, SET and reset round trip for every accepted webhook.
        this.properties=properties;this.json=json;this.clock=clock;
    }

    public AlertDtos.WebhookReceipt accept(VerifiedWebhook verified,
                                            WebhookAlertPayload payload) {
        for (int attempt=0; attempt<MAX_INCIDENT_RACE_RETRIES; attempt++) {
            try {
                return Objects.requireNonNull(transactions.execute(tx -> acceptOnce(verified,payload)));
            }
            catch (IncidentRace retry) {
                // The losing transaction has rolled back its nonce/event. A fresh DB
                // transaction now observes and aggregates into the authoritative winner.
                concurrencyBackoff(attempt);
            }
            catch (ConcurrencyFailureException retry) {
                // MySQL can select a loser while concurrent first-generation inserts
                // contend on the active-fingerprint unique guard. The whole local
                // transaction (including nonce/event) has rolled back, so retry it as
                // the same logical request and let the committed Incident arbitrate.
                concurrencyBackoff(attempt);
            }
            catch (EventRace race) {
                AlertRepository.EventRow existing = awaitEvent(verified,payload);
                throw eventConflict(existing,verified,payload);
            }
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"INCIDENT_CONCURRENCY_RETRY_EXHAUSTED",
                "Incident 并发裁决暂时不可用。",true);
    }

    private AlertDtos.WebhookReceipt acceptOnce(VerifiedWebhook verified,
                                                  WebhookAlertPayload payload) {
        Instant receivedAt=clock.instant().truncatedTo(ChronoUnit.MICROS);
        try {
            repository.insertNoncePreTrust(verified.tenantId(),UUID.randomUUID(),
                    verified.integrationId(),AlertIdempotencyCodec.sha256(
                            verified.nonce().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    verified.requestTimestamp(),receivedAt,
                    receivedAt.plus(properties.getTimestampWindow()));
        }
        catch (DuplicateKeyException replay) {
            throw ApiException.conflict("WEBHOOK_NONCE_REPLAYED","Webhook nonce 已被使用。");
        }
        Decision decision=decide(verified,payload);
        UUID alertEventId=UUID.randomUUID();
        try {
            repository.insertEventPreTrust(verified.tenantId(),alertEventId,
                    verified.integrationId(),payload.eventId(),payload.eventType(),
                    payload.fingerprint(),payload.cycleRef(),payload.occurredAt(),receivedAt,
                    decision.disposition,decision.incidentId,verified.rawBodySha256(),
                    payload.summary(),verified.correlationId());
        }
        catch (DuplicateKeyException collision) { throw new EventRace(); }

        TenantContext trusted=TenantContext.ofWebhook(verified.tenantId(),
                verified.integrationId(),verified.correlationId());
        List<AsyncProgress> async=new ArrayList<>();
        try(TenantContextScope ignored=TenantContextScope.open(trusted)) {
            if (decision.action == Action.RECORD_RECOVERY) {
                var legacy = decision.legacyRecovery;
                try {
                    repository.insertRecoveredIncident(verified.tenantId(), decision.incidentId,
                            payload.fingerprint(), decision.generation,
                            legacy == null ? alertEventId : legacy.alertEventId(),
                            legacy == null ? receivedAt : legacy.receivedAt(),
                            legacy == null ? payload.summary() : legacy.summary(),
                            legacy == null ? verified.integrationId() : legacy.integrationId());
                } catch (DuplicateKeyException collision) { throw new IncidentRace(); }
                repository.insertAudit(verified.tenantId(), UUID.randomUUID(), "INCIDENT_RECOVERY_RECORDED",
                        "INCIDENT", decision.incidentId, "SUCCEEDED", "RECOVERY_BEFORE_FIRING", null,
                        "INTEGRATION", verified.integrationId(), verified.correlationId(), null, receivedAt);
            }
            else if(decision.action==Action.CREATE) {
                try {
                    repository.insertIncident(verified.tenantId(),decision.incidentId,
                            payload.fingerprint(),decision.generation,alertEventId,receivedAt,
                            payload.summary(),verified.integrationId());
                }
                catch(DuplicateKeyException collision){throw new IncidentRace();}
                byte[] createIntent=AlertIdempotencyCodec.derivedKey(
                        AlertEventContract.TICKET_CREATE_REQUESTED.intentNamespace(),
                        verified.tenantId(),decision.incidentId);
                repository.insertTicketLink(verified.tenantId(),decision.incidentId,
                        createIntent,receivedAt);
                insertCreateOutbox(verified,payload,decision,createIntent,receivedAt);
                repository.insertAudit(verified.tenantId(),UUID.randomUUID(),"INCIDENT_OPENED",
                        "INCIDENT",decision.incidentId,"SUCCEEDED",null,null,"INTEGRATION",
                        verified.integrationId(),verified.correlationId(),null,receivedAt);
                async.add(new AsyncProgress("INCIDENT_TICKET","REQUESTED",decision.incidentId));
            }
            else if(decision.action==Action.AGGREGATE) {
                requireOne(repository.aggregateFiring(verified.tenantId(),decision.active,
                        alertEventId,receivedAt,payload.summary(),verified.integrationId()));
            }
            else if(decision.action==Action.RECOVER) {
                requireOne(repository.recoverIncident(verified.tenantId(),decision.active,
                        alertEventId,receivedAt,payload.summary(),verified.integrationId()));
                AlertRepository.LinkRow link=repository.findLink(verified.tenantId(),
                        decision.incidentId,true).orElseThrow();
                insertRecoveredOutbox(verified,decision.incidentId,alertEventId,
                        link.createIntent(),receivedAt);
                repository.insertAudit(verified.tenantId(),UUID.randomUUID(),"INCIDENT_RECOVERED",
                        "INCIDENT",decision.incidentId,"SUCCEEDED",null,null,"INTEGRATION",
                        verified.integrationId(),verified.correlationId(),null,receivedAt);
                async.add(new AsyncProgress("INCIDENT_RECOVERY_TIMELINE","PENDING",
                        decision.incidentId));
                reconcileWaitingCycles(verified.tenantId(), payload.fingerprint(), payload.occurredAt(), receivedAt);
            }
            repository.insertAudit(verified.tenantId(),UUID.randomUUID(),"ALERT_EVENT_ACCEPTED",
                    "ALERT_EVENT",alertEventId,"SUCCEEDED",decision.disposition,null,
                    "INTEGRATION",verified.integrationId(),verified.correlationId(),null,receivedAt);
        }
        String status=decision.action==Action.RECOVER?"RECOVERED":decision.incidentStatus;
        return new AlertDtos.WebhookReceipt(alertEventId,payload.eventId(),decision.disposition,
                decision.incidentId,status,HexFormat.of().formatHex(verified.rawBodySha256()),
                receivedAt,List.copyOf(async));
    }

    private Decision decide(VerifiedWebhook verified,WebhookAlertPayload payload) {
        var active=repository.findActiveForUpdatePreTrust(verified.tenantId(),payload.fingerprint());
        if(active.isPresent()) {
            AlertRepository.IncidentCycleRow row=active.get();
            if(Objects.equals(row.cycleRef(),payload.cycleRef())) {
                return new Decision("APPLIED",row.incidentId(),row.status(),0,row,
                        "FIRING".equals(payload.eventType())?Action.AGGREGATE:Action.RECOVER);
            }
            return historical(verified,payload);
        }
        List<AlertRepository.IncidentCycleRow> history=repository.findByCyclePreTrust(
                verified.tenantId(),payload.fingerprint(),payload.cycleRef());
        if(history.size()==1) {
            var old=history.get(0);return new Decision("LATE_OLD_GENERATION",old.incidentId(),
                    old.status(),0,null,Action.NONE);
        }
        if(!history.isEmpty()) {
            return new Decision("REVIEW_REQUIRED",null,null,0,null,Action.NONE);
        }
        // Earlier releases kept recovery-first messages as unlinked facts. Honor
        // those facts on upgrade without changing their original audit record.
        var legacyRecovery = repository.findUnlinkedRecoveryPreTrust(
                verified.tenantId(), payload.fingerprint(), payload.cycleRef());
        if (legacyRecovery.isPresent()) {
            return new Decision("LATE_OLD_GENERATION", UUID.randomUUID(), "RECOVERED",
                    repository.nextGenerationPreTrust(verified.tenantId(), payload.fingerprint()),
                    null, Action.RECORD_RECOVERY, legacyRecovery.get());
        }
        if ("RESOLVED".equals(payload.eventType())) {
            return new Decision("APPLIED", UUID.randomUUID(), "RECOVERED",
                    repository.nextGenerationPreTrust(verified.tenantId(), payload.fingerprint()),
                    null, Action.RECORD_RECOVERY);
        }
        return new Decision("APPLIED",UUID.randomUUID(),"ACTIVE",
                repository.nextGenerationPreTrust(verified.tenantId(),payload.fingerprint()),
                null,Action.CREATE);
    }

    private Decision historical(VerifiedWebhook verified,WebhookAlertPayload payload) {
        List<AlertRepository.IncidentCycleRow> rows=repository.findByCyclePreTrust(
                verified.tenantId(),payload.fingerprint(),payload.cycleRef());
        if(rows.size()==1) {
            var old=rows.get(0);return new Decision("LATE_OLD_GENERATION",old.incidentId(),
                    old.status(),0,null,Action.NONE);
        }
        return new Decision("REVIEW_REQUIRED",null,null,0,null,Action.NONE);
    }

    public void reconcilePersistedCycles(UUID tenantId, String fingerprint, Instant recoveredAt) {
        transactions.executeWithoutResult(tx -> {
            if(repository.findActiveForUpdatePreTrust(tenantId,fingerprint).isEmpty())
                reconcileWaitingCycles(tenantId,fingerprint,recoveredAt,clock.instant().truncatedTo(ChronoUnit.MICROS));
        });
    }

    private void reconcileWaitingCycles(UUID tenantId, String fingerprint, Instant recoveredAt, Instant now) {
        for (var event : repository.findWaitingCycles(tenantId,fingerprint,recoveredAt)) {
            if (!repository.findByCyclePreTrust(tenantId,fingerprint,event.cycleRef()).isEmpty()) continue;
            UUID id=UUID.randomUUID();
            int generation=repository.nextGenerationPreTrust(tenantId,fingerprint);
            var recovery=repository.findUnlinkedRecoveryPreTrust(tenantId,fingerprint,event.cycleRef());
            if (recovery.isPresent()) {
                var recovered=recovery.get();
                repository.insertRecoveredIncident(tenantId,id,fingerprint,generation,recovered.alertEventId(),
                        now,recovered.summary(),recovered.integrationId());
            } else {
                if (repository.findActiveForUpdatePreTrust(tenantId,fingerprint).isPresent()) return;
                try { repository.insertIncident(tenantId,id,fingerprint,generation,event.alertEventId(),
                        now,event.summary(),event.integrationId()); }
                catch (DuplicateKeyException race) { throw new IncidentRace(); }
                byte[] intent=AlertIdempotencyCodec.derivedKey(AlertEventContract.TICKET_CREATE_REQUESTED.intentNamespace(),tenantId,id);
                repository.insertTicketLink(tenantId,id,intent,now);
                // The source event was already authenticated and durably accepted; no nonce or webhook is replayed.
                var source=new VerifiedWebhook(tenantId,event.integrationId(),event.integrationCode(),event.integrationName(),
                        now.toString(),now,"",event.correlationId(),new byte[0],event.rawBodySha256());
                var payload=new WebhookAlertPayload(event.externalEventId(),event.eventType(),fingerprint,event.cycleRef(),event.occurredAt(),event.summary());
                insertCreateOutbox(source,payload,new Decision("APPLIED",id,"ACTIVE",generation,null,Action.CREATE),intent,now);
            }
            repository.insertAudit(tenantId,UUID.randomUUID(),"ALERT_CYCLE_RECONCILED","INCIDENT",id,"SUCCEEDED",
                    "已处理因前一轮故障尚未恢复而等待的告警。",null,"INTEGRATION",event.integrationId(),event.correlationId(),null,now);
        }
    }

    private void insertCreateOutbox(VerifiedWebhook verified,WebhookAlertPayload payload,
                                    Decision decision,byte[] intent,Instant now) {
        LinkedHashMap<String,Object> body=new LinkedHashMap<>();
        body.put("schemaVersion","1");body.put("incidentId",decision.incidentId.toString());
        body.put("createTicketIntentKey",AlertIdempotencyCodec.hex(intent));
        body.put("fingerprint",payload.fingerprint());body.put("generationNo",decision.generation);
        body.put("cycleRef",payload.cycleRef());body.put("summary",payload.summary());
        body.put("openedAt",MICROS.format(now));
        insertOutbox(verified,decision.incidentId,AlertEventContract.TICKET_CREATE_REQUESTED,
                intent,json(body),null,now,"INTEGRATION");
    }

    private void insertRecoveredOutbox(VerifiedWebhook verified,UUID incidentId,
                                       UUID recoveryEventId,byte[] createIntent,Instant now) {
        byte[] intent=AlertIdempotencyCodec.derivedKey(
                AlertEventContract.INCIDENT_RECOVERED.intentNamespace(),
                verified.tenantId(),incidentId);
        LinkedHashMap<String,Object> body=new LinkedHashMap<>();
        body.put("schemaVersion","1");body.put("incidentId",incidentId.toString());
        body.put("createTicketIntentKey",AlertIdempotencyCodec.hex(createIntent));
        body.put("recoveryEventId",recoveryEventId.toString());
        body.put("recoveredAt",MICROS.format(now));
        insertOutbox(verified,incidentId,AlertEventContract.INCIDENT_RECOVERED,
                intent,json(body),null,now,"INTEGRATION");
    }

    private void insertOutbox(VerifiedWebhook verified,UUID incidentId,
                              AlertEventContract.Binding binding,byte[] intent,byte[] body,
                              UUID causation,Instant now,String creator) {
        UUID messageId=UUID.randomUUID();
        repository.insertOutbox(verified.tenantId(),UUID.randomUUID(),messageId,
                AlertIdempotencyCodec.publicationKey(verified.tenantId(),messageId),intent,
                incidentId,binding.contractRef(),body,AlertIdempotencyCodec.sha256(body),
                verified.correlationId(),causation,now,creator);
    }

    private byte[] json(LinkedHashMap<String,Object> value) {
        try{return json.writeValueAsBytes(value);}
        catch(Exception impossible){throw new IllegalStateException("Unable to encode M7 payload",impossible);}
    }
    private AlertRepository.EventRow awaitEvent(VerifiedWebhook verified,
                                                WebhookAlertPayload payload) {
        for(int i=0;i<20;i++) {
            var row=repository.findEventPreTrust(verified.tenantId(),verified.integrationId(),
                    payload.eventId());if(row.isPresent())return row.get();Thread.onSpinWait();
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"WEBHOOK_EVENT_RACE_UNKNOWN",
                "Webhook eventId 并发结果暂不可读。",true);
    }
    private static ApiException eventConflict(AlertRepository.EventRow existing,
                                              VerifiedWebhook verified,
                                              WebhookAlertPayload payload) {
        boolean same=existing.eventType().equals(payload.eventType())
                && existing.fingerprint().equals(payload.fingerprint())
                && existing.cycleRef().equals(payload.cycleRef())
                && existing.occurredAt().equals(payload.occurredAt())
                && Objects.equals(existing.summary(),payload.summary())
                && MessageDigest.isEqual(existing.rawBodySha256(),verified.rawBodySha256());
        return ApiException.conflict(same?"WEBHOOK_EVENT_DUPLICATE":"WEBHOOK_EVENT_CONFLICT",
                "Webhook eventId 已存在。");
    }
    private static void requireOne(int value){if(value!=1)throw new IncidentRace();}
    private static void concurrencyBackoff(int attempt) {
        long millis=Math.min(50L,1L<<Math.min(attempt,5));
        try { Thread.sleep(millis); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "INCIDENT_CONCURRENCY_INTERRUPTED","Incident 并发裁决已中断。",true);
        }
    }
    private enum Action{CREATE,AGGREGATE,RECOVER,RECORD_RECOVERY,NONE}
    private record Decision(String disposition,UUID incidentId,String incidentStatus,
                            int generation,AlertRepository.IncidentCycleRow active,Action action,
                            AlertRepository.EventRow legacyRecovery) {
        Decision(String disposition, UUID incidentId, String incidentStatus, int generation,
                 AlertRepository.IncidentCycleRow active, Action action) {
            this(disposition, incidentId, incidentStatus, generation, active, action, null);
        }
    }
    private static final class IncidentRace extends RuntimeException{}
    private static final class EventRace extends RuntimeException{}
}
