package com.flowdesk.ticket.controller;

import com.flowdesk.ticket.dto.*;

import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.PageMeta;
import com.flowdesk.common.web.RequestCorrelation;
import com.flowdesk.common.web.SuccessEnvelope;
import com.flowdesk.ticket.service.PageResult;
import com.flowdesk.ticket.service.TicketService;
import com.flowdesk.ticket.service.TicketQueryService;
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

/**
 * API-021 至 API-033：工单生命周期、评论、时间线及 SLA 查询入口。
 * 负责 HTTP 参数、ETag 和幂等响应头，业务裁决交由命令与查询服务。
 */
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
    private final TicketQueryService queries;

    /**
     * 构造工单组件并保存调用依赖。
     */
    public TicketController(TicketService service, TicketQueryService queries) {
        this.service = service;
        this.queries = queries;
    }

    /**
     * 校验工单创建请求并保存事实，按接口约定处理幂等及版本条件。
     */
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

    /**
     * 按筛选条件与稳定游标分页查询工单数据，返回结果及后续页边界。
     */
    @GetMapping
    public SuccessEnvelope<java.util.List<TicketDtos.TicketResponse>> list(
            @RequestParam MultiValueMap<String, String> params) {
        PageResult<TicketDtos.TicketResponse> page = queries.list(
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

    /**
     * 通过 SLA 时钟定位工单，并再次校验该工单对当前用户是否可见。
     */
    @GetMapping("/by-sla-clock/{clockId}")
    public SuccessEnvelope<java.util.Map<String, UUID>> ticketForSlaClock(
            @PathVariable UUID clockId, @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        return new SuccessEnvelope<>(queries.ticketForSlaClock(clockId), new ApiMeta(RequestCorrelation.current()));
    }

    /**
     * 统计当前权限范围内的待处理工单数量，供工作台展示。
     */
    @GetMapping("/summary")
    public SuccessEnvelope<java.util.Map<String, Long>> summary(
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        return new SuccessEnvelope<>(queries.summary(), new ApiMeta(RequestCorrelation.current()));
    }

    /**
     * 读取指定工单详情，并依据当前租户与权限校验资源可见性。
     */
    @GetMapping("/{ticketId}")
    public ResponseEntity<SuccessEnvelope<TicketDtos.TicketResponse>> get(
            @PathVariable UUID ticketId,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        TicketDtos.TicketResponse ticket = queries.get(ticketId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, "\"lv:" + ticket.lockVersion() + "\"")
                .body(new SuccessEnvelope<>(ticket, new ApiMeta(RequestCorrelation.current())));
    }

    /**
     * 校验处理人资格、幂等键和预期版本后分派工单，返回流转结果。
     */
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

    /**
     * 受理处理人的接单命令，按版本和状态约束推进到处理中。
     */
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

    /**
     * 校验新处理人资格并转派工单，记录原因和状态变化。
     */
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

    /**
     * 校验工单可见性后分页读取评论，以创建时间与标识形成稳定边界。
     */
    @GetMapping("/{ticketId}/comments")
    public SuccessEnvelope<java.util.List<TicketDtos.TicketCommentResponse>> comments(
            @PathVariable UUID ticketId,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.single(params, "pageSize", COMMENT_PARAMS);
        PageResult<TicketDtos.TicketCommentResponse> page = queries.comments(
                ticketId, QueryParams.pageSize(params.getFirst("pageSize")),
                params.getFirst("pageToken"), params.getFirst("sort"));
        return new SuccessEnvelope<>(page.items(), new ApiMeta(RequestCorrelation.current(),
                new PageMeta(page.items().size(), page.nextPageToken(), page.sort())));
    }

    /**
     * 为可见工单保存评论，使用幂等键避免重试重复追加。
     */
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

    /**
     * 校验解决命令并记录解决原因，按生命周期规则结算 SLA 目标。
     */
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

    /**
     * 关闭组件持有的资源并终止后续处理。
     */
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

    /**
     * 校验重开条件后恢复处理中，保留处理人并推进解决周期。
     */
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

    /**
     * 按事件类型及发生时间筛选工单时间线，返回当前页与后续游标。
     */
    @GetMapping("/{ticketId}/timeline")
    public SuccessEnvelope<java.util.List<TicketDtos.TimelineEntryResponse>> timeline(
            @PathVariable UUID ticketId,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.single(params, "eventType", TIMELINE_PARAMS);
        PageResult<TicketDtos.TimelineEntryResponse> page = queries.timeline(
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

    /**
     * 汇总工单 SLA 时钟、触发事实与收件人快照，形成详情页状态响应。
     */
    @GetMapping("/{ticketId}/sla-status")
    public SuccessEnvelope<TicketDtos.SlaStatusResponse> slaStatus(
            @PathVariable UUID ticketId,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        return new SuccessEnvelope<>(queries.slaStatus(ticketId),
                new ApiMeta(RequestCorrelation.current()));
    }

    /**
     * 读取工作流启动状态，并将版本写入 ETag 响应头。
     */
    @GetMapping("/{ticketId}/workflow-start")
    public ResponseEntity<SuccessEnvelope<TicketDtos.WorkflowStartStatusResponse>> workflowStart(
            @PathVariable UUID ticketId,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        TicketDtos.WorkflowStartStatusResponse status = queries.workflowStartStatus(ticketId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, "\"lv:" + status.lockVersion() + "\"")
                .body(new SuccessEnvelope<>(status, new ApiMeta(RequestCorrelation.current())));
    }

    /**
     * 统一封装流转响应，附带锁版本 ETag 和幂等重放响应头。
     */
    private static ResponseEntity<SuccessEnvelope<TicketDtos.TicketResponse>> ticketResponse(
            TicketService.TransitionResult result) {
        return ResponseEntity.ok()
                .header("Idempotency-Replayed", result.replayed() ? "true" : null)
                .header(HttpHeaders.ETAG, "\"lv:" + result.ticket().lockVersion() + "\"")
                .body(new SuccessEnvelope<>(result.ticket(), new ApiMeta(RequestCorrelation.current())));
    }
}
