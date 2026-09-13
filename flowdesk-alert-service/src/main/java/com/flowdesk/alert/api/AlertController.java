package com.flowdesk.alert.api;

import com.flowdesk.alert.repository.AlertRepository;
import com.flowdesk.alert.service.AlertQueryService;
import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.web.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.UUID;

@RestController
public class AlertController {
    private static final Set<String> EVENT_PARAMS=Set.of("eventType","disposition","incidentId",
            "fingerprint","cycleRef","externalEventId","receivedFrom","receivedTo","pageSize","pageToken","sort");
    private static final Set<String> INCIDENT_PARAMS=Set.of("status","fingerprint","ticketLinkStatus",
            "openedFrom","openedTo","pageSize","pageToken","sort");
    private final AlertQueryService service;
    public AlertController(AlertQueryService service){this.service=service;}

    @GetMapping("/api/v1/alert-events") @RequiresPermission(PermissionCode.ALERT_EVENT_READ_TENANT)
    public SuccessEnvelope<java.util.List<AlertDtos.AlertEventView>> events(@RequestParam MultiValueMap<String,String> p){
        AlertQueryParams.validate(p,EVENT_PARAMS);int size=AlertQueryParams.pageSize(p.getFirst("pageSize"));
        var page=service.events(new AlertRepository.EventFilter(p.getFirst("eventType"),p.getFirst("disposition"),
                AlertQueryParams.uuid(p.getFirst("incidentId")),p.getFirst("fingerprint"),p.getFirst("cycleRef"),
                p.getFirst("externalEventId"),AlertQueryParams.instant(p.getFirst("receivedFrom")),
                AlertQueryParams.instant(p.getFirst("receivedTo"))),p.getFirst("sort"),size,p.getFirst("pageToken"));
        return new SuccessEnvelope<>(page.items(),new ApiMeta(RequestCorrelation.current(),new PageMeta(page.items().size(),page.next(),page.sort())));
    }
    @GetMapping("/api/v1/alert-events/{id}") @RequiresPermission(PermissionCode.ALERT_EVENT_READ_TENANT)
    public SuccessEnvelope<AlertDtos.AlertEventView> event(@PathVariable UUID id,@RequestParam MultiValueMap<String,String> p){
        AlertQueryParams.validate(p,Set.of());return new SuccessEnvelope<>(service.event(id),new ApiMeta(RequestCorrelation.current()));}
    @GetMapping("/api/v1/incidents") @RequiresPermission(PermissionCode.INCIDENT_READ_TENANT)
    public SuccessEnvelope<java.util.List<AlertDtos.IncidentView>> incidents(@RequestParam MultiValueMap<String,String> p){
        AlertQueryParams.validate(p,INCIDENT_PARAMS);int size=AlertQueryParams.pageSize(p.getFirst("pageSize"));
        var page=service.incidents(new AlertRepository.IncidentFilter(p.getFirst("status"),p.getFirst("fingerprint"),
                p.getFirst("ticketLinkStatus"),AlertQueryParams.instant(p.getFirst("openedFrom")),
                AlertQueryParams.instant(p.getFirst("openedTo"))),p.getFirst("sort"),size,p.getFirst("pageToken"));
        return new SuccessEnvelope<>(page.items(),new ApiMeta(RequestCorrelation.current(),new PageMeta(page.items().size(),page.next(),page.sort())));
    }
    @GetMapping("/api/v1/incidents/summary") @RequiresPermission(PermissionCode.INCIDENT_READ_TENANT)
    public SuccessEnvelope<java.util.Map<String, Long>> incidentSummary(@RequestParam MultiValueMap<String,String> p) {
        AlertQueryParams.validate(p, Set.of());
        return new SuccessEnvelope<>(service.incidentSummary(), new ApiMeta(RequestCorrelation.current()));
    }
    @GetMapping("/api/v1/incidents/{id}") @RequiresPermission(PermissionCode.INCIDENT_READ_TENANT)
    public ResponseEntity<SuccessEnvelope<AlertDtos.IncidentView>> incident(@PathVariable UUID id,@RequestParam MultiValueMap<String,String> p){
        AlertQueryParams.validate(p,Set.of());var value=service.incident(id);return ResponseEntity.ok()
                .header(HttpHeaders.ETAG,"\"lv:"+value.lockVersion()+"\"")
                .body(new SuccessEnvelope<>(value,new ApiMeta(RequestCorrelation.current())));}
    @GetMapping("/api/v1/incidents/{id}/ticket-link") @RequiresPermission(PermissionCode.INCIDENT_READ_TENANT)
    public ResponseEntity<SuccessEnvelope<AlertDtos.IncidentTicketLinkView>> link(@PathVariable UUID id,@RequestParam MultiValueMap<String,String> p){
        AlertQueryParams.validate(p,Set.of());var value=service.link(id);return ResponseEntity.ok()
                .header(HttpHeaders.ETAG,"\"lv:"+value.lockVersion()+"\"")
                .body(new SuccessEnvelope<>(value,new ApiMeta(RequestCorrelation.current())));}
}
