package com.flowdesk.alert.controller;
import com.flowdesk.alert.recovery.*;
import com.flowdesk.alert.service.AlertRecoveryQueryService;

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
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 告警消息人工恢复的 HTTP 入口，提供租户内列表、详情与重放命令。
 *
 * <p>本类负责参数形状、权限声明和响应头；恢复状态、幂等意图与事务裁决交给 {@link AlertRecoveryService}。
 * 阅读时可先看 get 如何返回 ETag，再看 replay 如何要求 If-Match、Idempotency-Key 和人工原因。
 *
 * <p>接口随工单结果业务入口开关 entry.enabled 一起启用；该开关与 Broker 消费器的 consumer.enabled 独立。
 * 只关闭 Broker 消费器时，人工恢复仍可调用业务消费者处理已持久化的消息。
 */
@RestController
@RequestMapping("/api/v1/alert-message-recoveries")
@ConditionalOnProperty(
        prefix = "flowdesk.alert.ticket-result.entry",
        name = "enabled",
        havingValue = "true")
public class AlertRecoveryController {
    private static final Set<String> PARAMS =
            Set.of(
                    "direction",
                    "state",
                    "deadLettered",
                    "lastErrorCode",
                    "recoveryRequiredFrom",
                    "recoveryRequiredTo",
                    "pageSize",
                    "pageToken",
                    "sort");
    private final AlertRecoveryService service;
    private final AlertRecoveryQueryService queries;

    /**
     * 注入当前 HTTP 入口所需的业务用例，使请求校验及响应包装调用同一服务能力。
     */
    AlertRecoveryController(AlertRecoveryService s, AlertRecoveryQueryService queries) {
        service = s;
        this.queries = queries;
    }

    /** 先拒绝未知、重复和空参数，再解析类型；业务取值与游标范围由 Service 校验。 */
    @GetMapping
    @RequiresPermission(PermissionCode.RECOVERY_READ_TENANT)
    public SuccessEnvelope<java.util.List<AlertRecoveryView>> list(
            @RequestParam MultiValueMap<String, String> p) {
        validate(p);
        String d = p.getFirst("deadLettered");
        Boolean dead =
                d == null
                        ? null
                        : switch (d) {
                            case "true" -> true;
                            case "false" -> false;
                            default ->
                                    throw ApiException.badRequest(
                                            "INVALID_QUERY_PARAMETER",
                                            "deadLettered 必须是 true 或 false。");
                        };
        int size = size(p.getFirst("pageSize"));
        var page =
                queries.list(
                        p.getFirst("direction"),
                        p.getFirst("state"),
                        dead,
                        p.getFirst("lastErrorCode"),
                        instant(p.getFirst("recoveryRequiredFrom")),
                        instant(p.getFirst("recoveryRequiredTo")),
                        size,
                        p.getFirst("pageToken"),
                        p.getFirst("sort"));
        return new SuccessEnvelope<>(
                page.items(),
                new ApiMeta(
                        RequestCorrelation.current(),
                        new PageMeta(page.items().size(), page.next(), page.sort())));
    }

    /** ETag 来自恢复记录版本，供操作员基于已读状态提交后续人工命令。 */
    @GetMapping("/{recoveryId}")
    @RequiresPermission(PermissionCode.RECOVERY_READ_TENANT)
    public ResponseEntity<SuccessEnvelope<AlertRecoveryView>> get(@PathVariable UUID recoveryId) {
        var v = queries.get(recoveryId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AlertRecoverySupport.etag(v.lockVersion()))
                .body(new SuccessEnvelope<>(v, new ApiMeta(RequestCorrelation.current())));
    }

    /**
     * 返回 202 表示人工尝试已被接纳，业务结果仍由后续消费或发布决定。
     * 幂等重复请求同样返回当前恢复视图，并通过 Idempotency-Replayed 标明命中了既有接纳结果。
     */
    @PostMapping("/{recoveryId}/replays")
    @RequiresPermission(PermissionCode.RECOVERY_REPLAY_TENANT)
    public ResponseEntity<SuccessEnvelope<AlertRecoveryView>> replay(
            @PathVariable UUID recoveryId,
            @Valid @RequestBody ReplayCommand command,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestHeader(name = "If-Match", required = false) String match,
            @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty())
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "人工重放端点不接受 query 参数。");
        var r = service.replay(recoveryId, command.reason(), key, match);
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.ETAG, AlertRecoverySupport.etag(r.recovery().lockVersion()))
                .header("Idempotency-Replayed", Boolean.toString(r.replayed()))
                .body(
                        new SuccessEnvelope<>(
                                r.recovery(), new ApiMeta(RequestCorrelation.current())));
    }

    /** 命令只允许人工原因，未知字段直接拒绝，避免客户端误以为额外字段参与了恢复裁决。 */
    public record ReplayCommand(String reason) {
        /**
         * 拒绝当前载荷中的未知或不允许字段，以契约错误中止处理，防止客户端通过额外字段改变人工重放语义。
         */
        @JsonAnySetter
        public void reject(String f, Object ignored) {
            throw new IllegalArgumentException("Alert replay request contains unknown field: " + f);
        }
    }

    /**
     * 逐项检查恢复列表查询参数的白名单及单值约束，拒绝未知或重复参数。
     */
    private static void validate(MultiValueMap<String, String> p) {
        for (String k : p.keySet()) {
            if (!PARAMS.contains(k))
                throw ApiException.badRequest("UNKNOWN_QUERY_PARAMETER", "请求包含未声明 query 参数。");
            if (p.get(k) == null
                    || p.get(k).size() != 1
                    || p.getFirst(k) == null
                    || p.getFirst(k).isBlank())
                throw ApiException.badRequest("INVALID_QUERY_PARAMETER", k + " 必须出现一次且非空。");
        }
    }

    /**
     * 解析并限制恢复列表分页大小，非法数字或超出契约范围时返回查询参数错误。
     */
    private static int size(String v) {
        if (v == null) return 20;
        try {
            int n = Integer.parseInt(v);
            if (n < 1 || n > 100) throw new Exception();
            return n;
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "pageSize 必须为 1..100。");
        }
    }

    /**
     * 将受校验时间文本转换为 Instant，格式错误使用当前接口约定的参数或契约错误处理。
     */
    private static Instant instant(String v) {
        if (v == null) return null;
        try {
            return Instant.parse(v);
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "时间 query 参数无效。");
        }
    }
}
