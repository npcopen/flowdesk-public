package com.flowdesk.ticket.api;

import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.PageMeta;
import com.flowdesk.common.web.RequestCorrelation;
import com.flowdesk.common.web.SuccessEnvelope;
import com.flowdesk.ticket.service.PageResult;
import com.flowdesk.ticket.service.TicketService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

/** API-021..033 ticket lifecycle, comments, timeline and SLA status. */
@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private static final Set<String> LIST_PARAMS = Set.of(
            "status", "originType", "catalogItemId", "createdFrom", "createdTo",
            "scope", "pageSize", "pageToken", "sort");
    private static final Set<String> COMMENT_PARAMS = Set.of("pageSize", "pageToken", "sort");
    private static final Set<String> TIMELINE_PARAMS = Set.of(
            "eventType", "occurredFrom", "occurredTo", "pageSize", "pageToken", "sort");

    private final TicketService service;

    public TicketController(TicketService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<SuccessEnvelope<TicketDtos.TicketResponse>> create(
            @Valid @RequestBody TicketDtos.TicketCreateRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        TicketService.CreateResult result = service.create(request, idempotencyKey, ifMatch);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/tickets/{ticketId}")
                .buildAndExpand(result.ticket().ticketId()).toUri();
        return ResponseEntity.status(result.replayed() ? 200 : 201)
                .header("Idempotency-Replayed", result.replayed() ? "true" : null)
                .header(HttpHeaders.LOCATION, location.toString())
                .header(HttpHeaders.ETAG, "\"lv:" + result.ticket().lockVersion() + "\"")
                .body(new SuccessEnvelope<>(result.ticket(),
                        new ApiMeta(RequestCorrelation.current(), null, result.asyncEffects())));
    }

    @GetMapping
    public SuccessEnvelope<java.util.List<TicketDtos.TicketResponse>> list(
            @RequestParam MultiValueMap<String, String> params) {
        PageResult<TicketDtos.TicketResponse> page = service.list(
                QueryParams.single(params, "status", LIST_PARAMS),
                QueryParams.single(params, "originType", LIST_PARAMS),
                QueryParams.uuid(params.getFirst("catalogItemId")),
                QueryParams.instant(params.getFirst("createdFrom")),
                QueryParams.instant(params.getFirst("createdTo")),
                QueryParams.single(params, "scope", LIST_PARAMS),
                QueryParams.pageSize(params.getFirst("pageSize")),
                params.getFirst("pageToken"),
                params.getFirst("sort"));
        return new SuccessEnvelope<>(page.items(), new ApiMeta(RequestCorrelation.current(),
                new PageMeta(page.items().size(), page.nextPageToken(), page.sort())));
    }

    @GetMapping("/by-sla-clock/{clockId}")
    public SuccessEnvelope<java.util.Map<String, UUID>> ticketForSlaClock(
            @PathVariable UUID clockId, @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        return new SuccessEnvelope<>(service.ticketForSlaClock(clockId), new ApiMeta(RequestCorrelation.current()));
    }

    @GetMapping("/summary")
    public SuccessEnvelope<java.util.Map<String, Long>> summary(
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        return new SuccessEnvelope<>(service.summary(), new ApiMeta(RequestCorrelation.current()));
    }

    @GetMapping("/{ticketId}")
    public ResponseEntity<SuccessEnvelope<TicketDtos.TicketResponse>> get(
            @PathVariable UUID ticketId,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        TicketDtos.TicketResponse ticket = service.get(ticketId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, "\"lv:" + ticket.lockVersion() + "\"")
                .body(new SuccessEnvelope<>(ticket, new ApiMeta(RequestCorrelation.current())));
    }

    @PostMapping("/{ticketId}/assign")
    public ResponseEntity<SuccessEnvelope<TicketDtos.TicketResponse>> assign(
            @PathVariable UUID ticketId,
            @Valid @RequestBody TicketDtos.AssignmentCommand request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        TicketService.TransitionResult result = service.assign(
                ticketId, request, idempotencyKey, ifMatch);
        return ticketResponse(result);
    }

    @PostMapping("/{ticketId}/accept")
    public ResponseEntity<SuccessEnvelope<TicketDtos.TicketResponse>> accept(
            @PathVariable UUID ticketId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        TicketService.TransitionResult result = service.accept(ticketId, idempotencyKey, ifMatch);
        return ticketResponse(result);
    }

    @PostMapping("/{ticketId}/transfer")
    public ResponseEntity<SuccessEnvelope<TicketDtos.TicketResponse>> transfer(
            @PathVariable UUID ticketId,
            @Valid @RequestBody TicketDtos.TransferCommand request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        TicketService.TransitionResult result = service.transfer(
                ticketId, request, idempotencyKey, ifMatch);
        return ticketResponse(result);
    }

    @GetMapping("/{ticketId}/comments")
    public SuccessEnvelope<java.util.List<TicketDtos.TicketCommentResponse>> comments(
            @PathVariable UUID ticketId,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.single(params, "pageSize", COMMENT_PARAMS);
        PageResult<TicketDtos.TicketCommentResponse> page = service.comments(
                ticketId, QueryParams.pageSize(params.getFirst("pageSize")),
                params.getFirst("pageToken"), params.getFirst("sort"));
        return new SuccessEnvelope<>(page.items(), new ApiMeta(RequestCorrelation.current(),
                new PageMeta(page.items().size(), page.nextPageToken(), page.sort())));
    }

    @PostMapping("/{ticketId}/comments")
    public ResponseEntity<SuccessEnvelope<TicketDtos.TicketCommentResponse>> addComment(
            @PathVariable UUID ticketId,
            @Valid @RequestBody TicketDtos.CommentCreateRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        TicketService.CommentResult result = service.addComment(ticketId, request, idempotencyKey);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/tickets/{ticketId}/comments/{commentId}")
                .buildAndExpand(ticketId, result.comment().commentId()).toUri();
        return ResponseEntity.status(result.replayed() ? 200 : 201)
                .header("Idempotency-Replayed", result.replayed() ? "true" : null)
                .header(HttpHeaders.LOCATION, location.toString())
                .body(new SuccessEnvelope<>(result.comment(), new ApiMeta(RequestCorrelation.current())));
    }

    @PostMapping("/{ticketId}/resolve")
    public ResponseEntity<SuccessEnvelope<TicketDtos.TicketResponse>> resolve(
            @PathVariable UUID ticketId,
            @Valid @RequestBody TicketDtos.ResolveCommand request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        TicketService.TransitionResult result = service.resolve(
                ticketId, request, idempotencyKey, ifMatch);
        return ticketResponse(result);
    }

    @PostMapping("/{ticketId}/close")
    public ResponseEntity<SuccessEnvelope<TicketDtos.TicketResponse>> close(
            @PathVariable UUID ticketId,
            @Valid @RequestBody TicketDtos.CloseOrReopenCommand request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        TicketService.TransitionResult result = service.close(
                ticketId, request, idempotencyKey, ifMatch);
        return ticketResponse(result);
    }

    @PostMapping("/{ticketId}/reopen")
    public ResponseEntity<SuccessEnvelope<TicketDtos.TicketResponse>> reopen(
            @PathVariable UUID ticketId,
            @Valid @RequestBody TicketDtos.CloseOrReopenCommand request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        TicketService.TransitionResult result = service.reopen(
                ticketId, request, idempotencyKey, ifMatch);
        return ticketResponse(result);
    }

    @GetMapping("/{ticketId}/timeline")
    public SuccessEnvelope<java.util.List<TicketDtos.TimelineEntryResponse>> timeline(
            @PathVariable UUID ticketId,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.single(params, "eventType", TIMELINE_PARAMS);
        PageResult<TicketDtos.TimelineEntryResponse> page = service.timeline(
                ticketId,
                QueryParams.single(params, "eventType", TIMELINE_PARAMS),
                QueryParams.instant(params.getFirst("occurredFrom")),
                QueryParams.instant(params.getFirst("occurredTo")),
                QueryParams.pageSize(params.getFirst("pageSize")),
                params.getFirst("pageToken"),
                params.getFirst("sort"));
        return new SuccessEnvelope<>(page.items(), new ApiMeta(RequestCorrelation.current(),
                new PageMeta(page.items().size(), page.nextPageToken(), page.sort())));
    }

    @GetMapping("/{ticketId}/sla-status")
    public SuccessEnvelope<TicketDtos.SlaStatusResponse> slaStatus(
            @PathVariable UUID ticketId,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        return new SuccessEnvelope<>(service.slaStatus(ticketId),
                new ApiMeta(RequestCorrelation.current()));
    }

    @GetMapping("/{ticketId}/workflow-start")
    public ResponseEntity<SuccessEnvelope<TicketDtos.WorkflowStartStatusResponse>> workflowStart(
            @PathVariable UUID ticketId,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        TicketDtos.WorkflowStartStatusResponse status = service.workflowStartStatus(ticketId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, "\"lv:" + status.lockVersion() + "\"")
                .body(new SuccessEnvelope<>(status, new ApiMeta(RequestCorrelation.current())));
    }

    private static ResponseEntity<SuccessEnvelope<TicketDtos.TicketResponse>> ticketResponse(
            TicketService.TransitionResult result) {
        return ResponseEntity.ok()
                .header("Idempotency-Replayed", result.replayed() ? "true" : null)
                .header(HttpHeaders.ETAG, "\"lv:" + result.ticket().lockVersion() + "\"")
                .body(new SuccessEnvelope<>(result.ticket(), new ApiMeta(RequestCorrelation.current())));
    }
}
