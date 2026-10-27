package com.flowdesk.notification.controller;
import com.flowdesk.notification.dto.*;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.PageMeta;
import com.flowdesk.common.web.RequestCorrelation;
import com.flowdesk.common.web.RequiresPermission;
import com.flowdesk.common.web.SuccessEnvelope;
import com.flowdesk.notification.recovery.NotificationRecoveryQueryService;
import com.flowdesk.notification.recovery.NotificationRecoveryReplayService;

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

import java.util.Set;
import java.util.UUID;

/** 提供租户内通知恢复查询与人工重放接口，并传递幂等键和强 ETag 前置条件。 */
@RestController
@RequestMapping("/api/v1")
public class NotificationRecoveryController {
    private static final Set<String> RECOVERY_PARAMS = Set.of(
            "direction", "state", "deadLettered", "lastErrorCode", "recoveryRequiredFrom",
            "recoveryRequiredTo", "pageSize", "pageToken", "sort");
    private final NotificationRecoveryQueryService recovery;
    private final NotificationRecoveryReplayService replays;

    /**
     * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
     */
    public NotificationRecoveryController(NotificationRecoveryQueryService recovery,
                                          NotificationRecoveryReplayService replays) {
        this.recovery = recovery;
        this.replays = replays;
    }

    /**
     * 解析筛选条件后返回当前租户恢复列表，保持分页元数据与结果一致。
     */
    @GetMapping("/notification-message-recoveries")
    @RequiresPermission(PermissionCode.RECOVERY_READ_TENANT)
    public SuccessEnvelope<java.util.List<NotificationDtos.MessageRecoveryResponse>> recoveries(
            @RequestParam MultiValueMap<String, String> params) {
        NotificationQueryParams.validate(params, RECOVERY_PARAMS);
        var page = recovery.list(params.getFirst("direction"), params.getFirst("state"),
                parseBoolean(params.getFirst("deadLettered")), params.getFirst("lastErrorCode"),
                NotificationQueryParams.instant(params.getFirst("recoveryRequiredFrom")),
                NotificationQueryParams.instant(params.getFirst("recoveryRequiredTo")),
                params.getFirst("pageSize"), params.getFirst("pageToken"), params.getFirst("sort"));
        return new SuccessEnvelope<>(page.items(), new ApiMeta(RequestCorrelation.current(),
                new PageMeta(page.items().size(), page.nextPageToken(), page.sort())));
    }

    /**
     * 读取指定恢复记录并附带当前锁版本，供后续人工重放使用。
     */
    @GetMapping("/notification-message-recoveries/{recoveryId}")
    @RequiresPermission(PermissionCode.RECOVERY_READ_TENANT)
    public ResponseEntity<SuccessEnvelope<NotificationDtos.MessageRecoveryResponse>> recovery(
            @PathVariable UUID recoveryId,
            @RequestParam MultiValueMap<String, String> params) {
        NotificationQueryParams.validate(params, Set.of());
        var value = recovery.get(recoveryId);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, "\"lv:" + value.lockVersion() + "\"")
                .body(new SuccessEnvelope<>(value, new ApiMeta(RequestCorrelation.current())));
    }

    /**
     * 解析人工重放命令并交由恢复服务执行，将当前恢复视图与幂等标记写入响应。
     */
    @PostMapping("/notification-message-recoveries/{recoveryId}/replays")
    @RequiresPermission(PermissionCode.RECOVERY_REPLAY_TENANT)
    public ResponseEntity<SuccessEnvelope<NotificationDtos.MessageRecoveryResponse>> replay(
            @PathVariable UUID recoveryId,
            @Valid @RequestBody NotificationDtos.ReplayCommand request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam MultiValueMap<String, String> params) {
        NotificationQueryParams.validate(params, Set.of());
        var value = replays.replay(recoveryId, request, ifMatch, idempotencyKey);
        return ResponseEntity.accepted()
                .header(HttpHeaders.ETAG, "\"lv:" + value.lockVersion() + "\"")
                .header("Cache-Control", "no-store")
                .body(new SuccessEnvelope<>(value, new ApiMeta(RequestCorrelation.current())));
    }

    /**
     * 解析规范布尔值，拒绝不受支持的文本以避免错误筛选。
     */
    private static Boolean parseBoolean(String value) {
        if (value == null) return null;
        if ("true".equalsIgnoreCase(value)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
        throw com.flowdesk.common.web.ApiException.badRequest("INVALID_QUERY_PARAMETER", "deadLettered 必须为 true/false。");
    }
}
