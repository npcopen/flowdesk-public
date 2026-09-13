package com.flowdesk.workflow.start;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.PageMeta;
import com.flowdesk.common.web.RequestCorrelation;
import com.flowdesk.common.web.RequiresPermission;
import com.flowdesk.common.web.SuccessEnvelope;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.Set;
import java.time.Instant;

/** M6 activation of API-058/059/060 for Workflow-owned Inbox and Outbox recovery. */
@RestController
@RequestMapping("/api/v1/workflow-message-recoveries")
@ConditionalOnProperty(
        prefix = "flowdesk.workflow.start.entry",
        name = "enabled",
        havingValue = "true")
public class WorkflowRecoveryController {

    private final WorkflowRecoveryReplayService service;
    private final WorkflowRecoveryReadService readService;
    private static final Set<String> LIST_PARAMS = Set.of(
            "direction", "state", "deadLettered", "lastErrorCode",
            "recoveryRequiredFrom", "recoveryRequiredTo", "pageSize", "pageToken", "sort");

    public WorkflowRecoveryController(WorkflowRecoveryReplayService service,
                                      WorkflowRecoveryReadService readService) {
        this.service = service;
        this.readService = readService;
    }

    @GetMapping
    @RequiresPermission(PermissionCode.RECOVERY_READ_TENANT)
    public SuccessEnvelope<java.util.List<WorkflowRecoveryView>> list(
            @RequestParam MultiValueMap<String, String> params) {
        rejectUnknown(params, LIST_PARAMS);
        String direction = single(params, "direction");
        String state = single(params, "state");
        String deadText = single(params, "deadLettered");
        Boolean dead = deadText == null ? null : switch (deadText) {
            case "true" -> true; case "false" -> false;
            default -> throw ApiException.badRequest("INVALID_QUERY_PARAMETER",
                    "deadLettered 必须是 true 或 false。");
        };
        int pageSize = pageSize(single(params, "pageSize"));
        WorkflowRecoveryReadService.Page page = readService.list(direction, state, dead,
                single(params, "lastErrorCode"), instant(single(params, "recoveryRequiredFrom")),
                instant(single(params, "recoveryRequiredTo")), pageSize,
                single(params, "pageToken"), single(params, "sort"));
        return new SuccessEnvelope<>(page.items(), new ApiMeta(RequestCorrelation.current(),
                new PageMeta(page.items().size(), page.nextPageToken(), page.sort())));
    }

    @GetMapping("/{recoveryId}")
    @RequiresPermission(PermissionCode.RECOVERY_READ_TENANT)
    public ResponseEntity<SuccessEnvelope<WorkflowRecoveryView>> get(@PathVariable UUID recoveryId) {
        WorkflowRecoveryView view = readService.get(recoveryId);
        return ResponseEntity.ok().header(HttpHeaders.ETAG,
                WorkflowRecoverySupport.etag(view.lockVersion()))
                .body(new SuccessEnvelope<>(view, new ApiMeta(RequestCorrelation.current())));
    }

    @PostMapping("/{recoveryId}/replays")
    @RequiresPermission(PermissionCode.RECOVERY_REPLAY_TENANT)
    public ResponseEntity<SuccessEnvelope<WorkflowRecoveryView>> replay(
            @PathVariable UUID recoveryId,
            @Valid @RequestBody ReplayCommand command,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) {
            throw ApiException.badRequest(
                    "INVALID_QUERY_PARAMETER", "人工重放端点不接受 query 参数。");
        }
        WorkflowRecoveryReplayService.ReplayResult result = service.replay(
                recoveryId, command.reason(), idempotencyKey, ifMatch);
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.ETAG,
                        WorkflowRecoverySupport.etag(result.recovery().lockVersion()))
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(new SuccessEnvelope<>(result.recovery(),
                        new ApiMeta(RequestCorrelation.current())));
    }

    /** The only accepted HTTP input; message facts are intentionally absent. */
    public record ReplayCommand(String reason) {

        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignoredValue) {
            throw new IllegalArgumentException(
                    "Workflow replay request contains unknown field: " + field);
        }
    }

    private static void rejectUnknown(MultiValueMap<String, String> params, Set<String> allowed) {
        for (String key : params.keySet()) {
            if (!allowed.contains(key)) throw ApiException.badRequest(
                    "UNKNOWN_QUERY_PARAMETER", "请求包含未声明 query 参数。");
            if (params.get(key) == null || params.get(key).size() != 1
                    || params.getFirst(key) == null || params.getFirst(key).isBlank()) {
                throw ApiException.badRequest("INVALID_QUERY_PARAMETER", key + " 必须出现一次且非空。");
            }
        }
    }
    private static String single(MultiValueMap<String, String> params, String key) {
        return params.getFirst(key);
    }
    private static int pageSize(String value) {
        if (value == null) return 20;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 100) throw new NumberFormatException();
            return parsed;
        }
        catch (NumberFormatException invalid) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "pageSize 必须为 1..100。");
        }
    }
    private static Instant instant(String value) {
        if (value == null) return null;
        try { return Instant.parse(value); }
        catch (RuntimeException invalid) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "时间 query 参数无效。");
        }
    }
}
