package com.flowdesk.notification.controller;
import com.flowdesk.notification.dto.*;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.PageMeta;
import com.flowdesk.common.web.RequestCorrelation;
import com.flowdesk.common.web.RequiresPermission;
import com.flowdesk.common.web.SuccessEnvelope;
import com.flowdesk.notification.service.NotificationService;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

/** 提供当前用户通知列表、未读数和读状态修改接口，统一权限、ETag 与幂等响应。 */
@RestController
@RequestMapping("/api/v1")
public class NotificationController {
    private static final Set<String> LIST_PARAMS = Set.of(
            "state", "notificationKind", "createdFrom", "createdTo", "pageSize", "pageToken", "sort");
    private final NotificationService service;

    /**
     * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
     */
    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /**
     * 按声明的筛选和分页条件读取当前租户可见记录，返回本页数据及下一页边界。
     */
    @GetMapping("/notifications")
    @RequiresPermission(PermissionCode.NOTIFICATION_READ_SELF)
    public SuccessEnvelope<java.util.List<NotificationDtos.NotificationResponse>> list(
            @RequestParam MultiValueMap<String, String> params) {
        NotificationQueryParams.validate(params, LIST_PARAMS);
        var page = service.list(params.getFirst("state"), params.getFirst("notificationKind"),
                NotificationQueryParams.instant(params.getFirst("createdFrom")),
                NotificationQueryParams.instant(params.getFirst("createdTo")),
                NotificationQueryParams.pageSize(params.getFirst("pageSize")),
                params.getFirst("pageToken"), params.getFirst("sort"));
        return new SuccessEnvelope<>(page.items(), new ApiMeta(RequestCorrelation.current(),
                new PageMeta(page.items().size(), page.nextPageToken(), page.sort())));
    }

    /**
     * 统计当前用户未读通知数量，保持接收人及租户隔离。
     */
    @GetMapping("/notifications/unread-count")
    @RequiresPermission(PermissionCode.NOTIFICATION_READ_SELF)
    public SuccessEnvelope<NotificationDtos.UnreadCountResponse> unreadCount(
            @RequestParam MultiValueMap<String, String> params) {
        NotificationQueryParams.validate(params, Set.of());
        return new SuccessEnvelope<>(service.unreadCount(), new ApiMeta(RequestCorrelation.current()));
    }

    /**
     * 接收读状态写入，转交带幂等和版本保护的用例，并返回更新后的 ETag。
     */
    @PutMapping("/notifications/{notificationId}/read-state")
    @RequiresPermission(PermissionCode.NOTIFICATION_WRITE_SELF)
    public ResponseEntity<SuccessEnvelope<NotificationDtos.NotificationResponse>> readState(
            @PathVariable UUID notificationId,
            @Valid @RequestBody NotificationDtos.ReadStateWrite request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam MultiValueMap<String, String> params) {
        NotificationQueryParams.validate(params, Set.of());
        var result = service.updateReadState(notificationId, request, ifMatch, idempotencyKey);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, "\"lv:" + result.notification().lockVersion() + "\"")
                .header("Idempotency-Replayed", result.replayed() ? "true" : null)
                .body(new SuccessEnvelope<>(result.notification(), new ApiMeta(RequestCorrelation.current())));
    }

}
