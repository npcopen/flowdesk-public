package com.flowdesk.workflow.controller;

import com.flowdesk.workflow.dto.WorkflowRecoveryView;
import com.flowdesk.workflow.dto.WorkflowRecoverySupport;
import com.flowdesk.workflow.service.recovery.*;
import com.flowdesk.workflow.dto.*;

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

/**
 * 工作流所属 Inbox/Outbox 恢复记录的查询与受控重放接口（API-058～060）。
 */
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

    /**
     * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
     */
    public WorkflowRecoveryController(WorkflowRecoveryReplayService service,
                                      WorkflowRecoveryReadService readService) {
        this.service = service;
        this.readService = readService;
    }

    /**
     * 按声明的筛选和分页条件读取当前租户可见记录，返回本页数据及下一页边界。
     */
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

    /**
     * 读取当前租户内指定记录，不存在或不可见时返回统一资源错误。
     */
    @GetMapping("/{recoveryId}")
    @RequiresPermission(PermissionCode.RECOVERY_READ_TENANT)
    public ResponseEntity<SuccessEnvelope<WorkflowRecoveryView>> get(@PathVariable UUID recoveryId) {
        WorkflowRecoveryView view = readService.get(recoveryId);
        return ResponseEntity.ok().header(HttpHeaders.ETAG,
                WorkflowRecoverySupport.etag(view.lockVersion()))
                .body(new SuccessEnvelope<>(view, new ApiMeta(RequestCorrelation.current())));
    }

    /**
     * 解析人工重放命令并交由恢复服务执行，将当前恢复视图与幂等标记写入响应。
     */
    @PostMapping("/{recoveryId}/replays")
    @RequiresPermission(PermissionCode.RECOVERY_REPLAY_TENANT)
    public ResponseEntity<SuccessEnvelope<WorkflowRecoveryView>> replay(
            @PathVariable UUID recoveryId,
            @Valid @RequestBody WorkflowRecoveryReplayCommand command,
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

    /**
     * 拒绝未声明查询字段，避免拼写错误或额外条件被静默忽略。
     */
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
    /**
     * 核验查询字段白名单和单值约束，防止重复参数造成不明确解析。
     */
    private static String single(MultiValueMap<String, String> params, String key) {
        return params.getFirst(key);
    }
    /**
     * 解析分页大小并应用默认值，拒绝超出单次查询上限的请求。
     */
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
    /**
     * 解析规范时间查询值；缺失时保留为空，非法格式返回参数错误。
     */
    private static Instant instant(String value) {
        if (value == null) return null;
        try { return Instant.parse(value); }
        catch (RuntimeException invalid) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "时间 query 参数无效。");
        }
    }
}
