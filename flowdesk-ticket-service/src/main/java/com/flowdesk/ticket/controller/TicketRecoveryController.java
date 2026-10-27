package com.flowdesk.ticket.controller;

import com.flowdesk.ticket.recovery.*;

import com.flowdesk.ticket.dto.TicketRecoveryReplayCommand;
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

/** 工单消息恢复的查询与人工重放 HTTP 入口，校验恢复权限、幂等标识和 ETag 前置条件。 */
@RestController
@RequestMapping("/api/v1/ticket-message-recoveries")
@ConditionalOnExpression("${flowdesk.ticket.workflow-reverse.entry.enabled:false} or ${flowdesk.ticket.incident-entry.enabled:false}")
public class TicketRecoveryController {
    private static final Set<String> PARAMS = Set.of("direction", "state", "deadLettered",
            "lastErrorCode", "recoveryRequiredFrom", "recoveryRequiredTo",
            "pageSize", "pageToken", "sort");
    private final TicketRecoveryService service;
    /**
     * 构造工单组件并保存调用依赖。
     */
    public TicketRecoveryController(TicketRecoveryService service) { this.service = service; }

    /**
     * 按筛选条件与稳定游标分页查询工单数据，返回结果及后续页边界。
     */
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

    /**
     * 读取指定工单详情，并依据当前租户与权限校验资源可见性。
     */
    @GetMapping("/{recoveryId}")
    @RequiresPermission(PermissionCode.RECOVERY_READ_TENANT)
    public ResponseEntity<SuccessEnvelope<TicketRecoveryView>> get(@PathVariable UUID recoveryId) {
        TicketRecoveryView view = service.get(recoveryId);
        return ResponseEntity.ok().header(HttpHeaders.ETAG,
                etag(view.lockVersion()))
                .body(new SuccessEnvelope<>(view, new ApiMeta(RequestCorrelation.current())));
    }

    /**
     * 校验权限、状态、幂等键与版本后受理消息重放，返回恢复操作结果。
     */
    @PostMapping("/{recoveryId}/replays")
    @RequiresPermission(PermissionCode.RECOVERY_REPLAY_TENANT)
    public ResponseEntity<SuccessEnvelope<TicketRecoveryView>> replay(
            @PathVariable UUID recoveryId, @Valid @RequestBody TicketRecoveryReplayCommand command,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw ApiException.badRequest(
                "INVALID_QUERY_PARAMETER", "人工重放端点不接受 query 参数。");
        var result = service.replay(recoveryId, command.reason(), key, ifMatch);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.ETAG, etag(result.recovery().lockVersion()))
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(new SuccessEnvelope<>(result.recovery(),
                        new ApiMeta(RequestCorrelation.current())));
    }

    /**
     * 按锁版本构造 HTTP ETag，供后续 If-Match 并发控制。
     */
    private static String etag(long version) { return "\"lv:" + version + "\""; }

    /**
     * 校验查询参数白名单和重复项，拒绝未知参数或非法组合。
     */
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
    /**
     * 解析分页大小，补充默认值并校验单页处理上限。
     */
    private static int pageSize(String value) {
        if (value == null) return 20;
        try { int parsed = Integer.parseInt(value); if (parsed < 1 || parsed > 100) throw new Exception(); return parsed; }
        catch (Exception invalid) { throw ApiException.badRequest(
                "INVALID_QUERY_PARAMETER", "pageSize 必须为 1..100。"); }
    }
    /**
     * 解析时间文本或转换数据库时间值，遵循空值约定并保留业务精度。
     */
    private static Instant instant(String value) {
        if (value == null) return null;
        try { return Instant.parse(value); }
        catch (RuntimeException invalid) { throw ApiException.badRequest(
                "INVALID_QUERY_PARAMETER", "时间 query 参数无效。"); }
    }
}
