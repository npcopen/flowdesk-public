package com.flowdesk.ticket.recovery;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.PageMeta;
import com.flowdesk.common.web.RequestCorrelation;
import com.flowdesk.common.web.RequiresPermission;
import com.flowdesk.common.web.SuccessEnvelope;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ticket-message-recoveries")
@ConditionalOnExpression("${flowdesk.ticket.workflow-reverse.entry.enabled:false} or ${flowdesk.ticket.incident-entry.enabled:false}")
public class TicketRecoveryController {
    private static final Set<String> PARAMS = Set.of("direction", "state", "deadLettered",
            "lastErrorCode", "recoveryRequiredFrom", "recoveryRequiredTo",
            "pageSize", "pageToken", "sort");
    private final TicketRecoveryService service;
    public TicketRecoveryController(TicketRecoveryService service) { this.service = service; }

    @GetMapping
    @RequiresPermission(PermissionCode.RECOVERY_READ_TENANT)
    public SuccessEnvelope<java.util.List<TicketRecoveryView>> list(
            @RequestParam MultiValueMap<String, String> params) {
        validate(params);
        String deadText = params.getFirst("deadLettered");
        Boolean dead = deadText == null ? null : switch (deadText) {
            case "true" -> true; case "false" -> false;
            default -> throw ApiException.badRequest("INVALID_QUERY_PARAMETER",
                    "deadLettered 必须是 true 或 false。");
        };
        int size = pageSize(params.getFirst("pageSize"));
        var page = service.list(params.getFirst("direction"), params.getFirst("state"), dead,
                params.getFirst("lastErrorCode"), instant(params.getFirst("recoveryRequiredFrom")),
                instant(params.getFirst("recoveryRequiredTo")), size,
                params.getFirst("pageToken"), params.getFirst("sort"));
        return new SuccessEnvelope<>(page.items(), new ApiMeta(RequestCorrelation.current(),
                new PageMeta(page.items().size(), page.nextPageToken(), page.sort())));
    }

    @GetMapping("/{recoveryId}")
    @RequiresPermission(PermissionCode.RECOVERY_READ_TENANT)
    public ResponseEntity<SuccessEnvelope<TicketRecoveryView>> get(@PathVariable UUID recoveryId) {
        TicketRecoveryView view = service.get(recoveryId);
        return ResponseEntity.ok().header(HttpHeaders.ETAG,
                TicketRecoverySupport.etag(view.lockVersion()))
                .body(new SuccessEnvelope<>(view, new ApiMeta(RequestCorrelation.current())));
    }

    @PostMapping("/{recoveryId}/replays")
    @RequiresPermission(PermissionCode.RECOVERY_REPLAY_TENANT)
    public ResponseEntity<SuccessEnvelope<TicketRecoveryView>> replay(
            @PathVariable UUID recoveryId, @Valid @RequestBody ReplayCommand command,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw ApiException.badRequest(
                "INVALID_QUERY_PARAMETER", "人工重放端点不接受 query 参数。");
        var result = service.replay(recoveryId, command.reason(), key, ifMatch);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.ETAG, TicketRecoverySupport.etag(result.recovery().lockVersion()))
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(new SuccessEnvelope<>(result.recovery(),
                        new ApiMeta(RequestCorrelation.current())));
    }

    public record ReplayCommand(String reason) {
        @JsonAnySetter public void reject(String field, Object ignored) {
            throw new IllegalArgumentException("Ticket replay request contains unknown field: " + field);
        }
    }

    private static void validate(MultiValueMap<String, String> params) {
        for (String key : params.keySet()) {
            if (!PARAMS.contains(key)) throw ApiException.badRequest(
                    "UNKNOWN_QUERY_PARAMETER", "请求包含未声明 query 参数。");
            if (params.get(key) == null || params.get(key).size() != 1
                    || params.getFirst(key) == null || params.getFirst(key).isBlank()) {
                throw ApiException.badRequest("INVALID_QUERY_PARAMETER", key + " 必须出现一次且非空。");
            }
        }
    }
    private static int pageSize(String value) {
        if (value == null) return 20;
        try { int parsed = Integer.parseInt(value); if (parsed < 1 || parsed > 100) throw new Exception(); return parsed; }
        catch (Exception invalid) { throw ApiException.badRequest(
                "INVALID_QUERY_PARAMETER", "pageSize 必须为 1..100。"); }
    }
    private static Instant instant(String value) {
        if (value == null) return null;
        try { return Instant.parse(value); }
        catch (RuntimeException invalid) { throw ApiException.badRequest(
                "INVALID_QUERY_PARAMETER", "时间 query 参数无效。"); }
    }
}
