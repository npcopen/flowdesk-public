package com.flowdesk.alert.service;

import com.flowdesk.alert.api.AlertDtos;
import com.flowdesk.alert.repository.AlertRepository;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.web.ApiException;

import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class AlertQueryService {
    private static final Set<String> EVENT_SORTS=Set.of("-receivedAt","receivedAt","-occurredAt");
    private static final Set<String> INCIDENT_SORTS=Set.of("-lastEventAt","lastEventAt","-openedAt");
    private final AlertRepository repository;private final AlertCursorTokenService cursors;
    public AlertQueryService(AlertRepository repository,AlertCursorTokenService cursors){
        this.repository=repository;this.cursors=cursors;}

    public Page<AlertDtos.AlertEventView> events(AlertRepository.EventFilter filter,String sort,
                                                 int size,String token){
        if(filter.receivedFrom()!=null&&filter.receivedTo()!=null&&filter.receivedFrom().isAfter(filter.receivedTo()))invalid();
        oneOf(filter.eventType(),"FIRING","RESOLVED");oneOf(filter.disposition(),"APPLIED","LATE_OLD_GENERATION","REVIEW_REQUIRED");
        String normalized=sort==null?"-receivedAt":sort;if(!EVENT_SORTS.contains(normalized))unsupported();
        UUID tenant=TenantContextHolder.current().tenantId();String scope="alert-events|"+filter;
        var cursor=cursors.decode(token,tenant,scope,size,normalized);
        Instant time=cursor==null?null:Instant.parse(cursor.value());
        List<AlertRepository.EventRow> rows=repository.listEvents(tenant,filter,normalized,time,
                cursor==null?null:cursor.id(),size+1);boolean more=rows.size()>size;if(more)rows=rows.subList(0,size);
        String next=null;if(more&&!rows.isEmpty()){var last=rows.get(rows.size()-1);
            Instant value="-occurredAt".equals(normalized)?last.occurredAt():last.receivedAt();
            next=cursors.encode(tenant,scope,size,normalized,new AlertCursorTokenService.InstantCursor(value.toString(),last.alertEventId()));}
        return new Page<>(rows.stream().map(this::event).toList(),next,normalized);
    }
    public AlertDtos.AlertEventView event(UUID id){UUID tenant=TenantContextHolder.current().tenantId();
        return repository.findEvent(tenant,id).map(this::event).orElseThrow(AlertQueryService::notFound);}
    public Page<AlertDtos.IncidentView> incidents(AlertRepository.IncidentFilter filter,String sort,
                                                  int size,String token){
        if(filter.openedFrom()!=null&&filter.openedTo()!=null&&filter.openedFrom().isAfter(filter.openedTo()))invalid();
        oneOf(filter.status(),"ACTIVE","RECOVERED");oneOf(filter.ticketLinkStatus(),"REQUESTED","ESTABLISHED","RECOVERY_REQUIRED");
        String normalized=sort==null?"-lastEventAt":sort;if(!INCIDENT_SORTS.contains(normalized))unsupported();
        UUID tenant=TenantContextHolder.current().tenantId();String scope="incidents|"+filter;
        var cursor=cursors.decode(token,tenant,scope,size,normalized);Instant time=cursor==null?null:Instant.parse(cursor.value());
        List<AlertRepository.IncidentCycleRow> rows=repository.listIncidents(tenant,filter,normalized,time,
                cursor==null?null:cursor.id(),size+1);boolean more=rows.size()>size;if(more)rows=rows.subList(0,size);
        String next=null;if(more&&!rows.isEmpty()){var last=rows.get(rows.size()-1);
            Instant value="-openedAt".equals(normalized)?last.openedAt():last.lastEventAt();
            next=cursors.encode(tenant,scope,size,normalized,new AlertCursorTokenService.InstantCursor(value.toString(),last.incidentId()));}
        return new Page<>(rows.stream().map(AlertQueryService::incident).toList(),next,normalized);
    }
    public java.util.Map<String, Long> incidentSummary() {
        return java.util.Map.of("activeCount", repository.countActiveIncidents(TenantContextHolder.current().tenantId()));
    }
    public AlertDtos.IncidentView incident(UUID id){UUID tenant=TenantContextHolder.current().tenantId();
        return repository.findIncident(tenant,id,false).map(AlertQueryService::incident).orElseThrow(AlertQueryService::notFound);}
    public AlertDtos.IncidentTicketLinkView link(UUID id){UUID tenant=TenantContextHolder.current().tenantId();
        if(repository.findIncident(tenant,id,false).isEmpty())throw notFound();
        if (repository.isRecoveryOnlyIncident(tenant, id)) {
            return new AlertDtos.IncidentTicketLinkView(id, "NOT_REQUIRED", null, null, null,
                    null, null, 0, null, 0, null, null);
        }
        return repository.findLink(tenant,id,false).map(row->new AlertDtos.IncidentTicketLinkView(
                row.incidentId(),row.status(),row.ticketId(),row.requestedAt(),row.establishedAt(),
                row.recoveryRequiredAt(),row.recoveryReasonCode(),row.retryCount(),row.lastAttemptAt(),
                row.lockVersion(),row.createdAt(),row.updatedAt())).orElseThrow(AlertQueryService::notFound);}
    private AlertDtos.AlertEventView event(AlertRepository.EventRow row){
        UUID incidentId=row.incidentId(); String disposition=row.disposition();
        if(incidentId==null) {
            var cycles=repository.findByCyclePreTrust(TenantContextHolder.current().tenantId(),row.fingerprint(),row.cycleRef());
            if(cycles.size()==1) { incidentId=cycles.get(0).incidentId(); disposition="APPLIED"; }
        }
        return new AlertDtos.AlertEventView(
            row.alertEventId(),new AlertDtos.IntegrationRef(row.integrationId(),row.integrationCode(),row.integrationName()),
            row.externalEventId(),row.eventType(),row.fingerprint(),row.cycleRef(),row.occurredAt(),row.receivedAt(),
            disposition,incidentId,HexFormat.of().formatHex(row.rawBodySha256()),row.summary(),row.correlationId());}
    private static AlertDtos.IncidentView incident(AlertRepository.IncidentCycleRow row){return new AlertDtos.IncidentView(
            row.incidentId(),row.fingerprint(),row.generationNo(),row.status(),row.eventCount(),row.openedAt(),
            row.lastEventAt(),row.recoveredAt(),row.summary(),row.lockVersion(),row.createdAt(),row.updatedAt());}
    private static void oneOf(String value,String...allowed){if(value==null)return;if(!java.util.Arrays.asList(allowed).contains(value))invalid();}
    private static void unsupported(){throw ApiException.badRequest("SORT_UNSUPPORTED","排序方式不受支持。");}
    private static void invalid(){throw ApiException.badRequest("INVALID_QUERY_PARAMETER","query 参数无效。");}
    private static ApiException notFound(){return ApiException.notFound("RESOURCE_NOT_FOUND","Alert 资源不存在。");}
    public record Page<T>(List<T> items,String next,String sort){}
}
