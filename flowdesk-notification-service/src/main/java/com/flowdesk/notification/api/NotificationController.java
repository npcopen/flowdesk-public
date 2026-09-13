package com.flowdesk.notification.api;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.PageMeta;
import com.flowdesk.common.web.RequestCorrelation;
import com.flowdesk.common.web.SuccessEnvelope;
import com.flowdesk.common.web.RequiresPermission;
import com.flowdesk.notification.service.NotificationCursorTokenService;
import com.flowdesk.notification.service.NotificationRecoveryService;
import com.flowdesk.notification.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class NotificationController {
    private static final Set<String> LIST_PARAMS = Set.of(
            "state", "notificationKind", "createdFrom", "createdTo", "pageSize", "pageToken", "sort");
    private static final Set<String> RECOVERY_PARAMS = Set.of(
            "direction", "state", "deadLettered", "lastErrorCode", "recoveryRequiredFrom",
            "recoveryRequiredTo", "pageSize", "pageToken", "sort");

    private final NotificationService service;
    private final NotificationCursorTokenService cursors;
    private final NotificationRecoveryService recovery;

    public NotificationController(NotificationService service,
                                  NotificationCursorTokenService cursors,
                                  NotificationRecoveryService recovery) {
        this.service = service;
        this.cursors = cursors;
        this.recovery = recovery;
    }

    @GetMapping("/notifications")
    @RequiresPermission(PermissionCode.NOTIFICATION_READ_SELF)
    public SuccessEnvelope<java.util.List<NotificationDtos.NotificationResponse>> list(
            @RequestParam MultiValueMap<String, String> params) {
        NotificationQueryParams.validate(params, LIST_PARAMS);
        var page = service.list(params.getFirst("state"), params.getFirst("notificationKind"),
                NotificationQueryParams.instant(params.getFirst("createdFrom")),
                NotificationQueryParams.instant(params.getFirst("createdTo")),
                NotificationQueryParams.pageSize(params.getFirst("pageSize")),
                params.getFirst("pageToken"), params.getFirst("sort"), cursors);
        return new SuccessEnvelope<>(page.items(), new ApiMeta(RequestCorrelation.current(),
                new PageMeta(page.items().size(), page.nextPageToken(), page.sort())));
    }

    @GetMapping("/notifications/unread-count")
    @RequiresPermission(PermissionCode.NOTIFICATION_READ_SELF)
    public SuccessEnvelope<NotificationDtos.UnreadCountResponse> unreadCount(
            @RequestParam MultiValueMap<String, String> params) {
        NotificationQueryParams.validate(params, Set.of());
        return new SuccessEnvelope<>(service.unreadCount(), new ApiMeta(RequestCorrelation.current()));
    }

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

    @PostMapping("/notification-message-recoveries/{recoveryId}/replays")
    @RequiresPermission(PermissionCode.RECOVERY_REPLAY_TENANT)
    public ResponseEntity<SuccessEnvelope<NotificationDtos.MessageRecoveryResponse>> replay(
            @PathVariable UUID recoveryId,
            @Valid @RequestBody NotificationDtos.ReplayCommand request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam MultiValueMap<String, String> params) {
        NotificationQueryParams.validate(params, Set.of());
        var value = recovery.replay(recoveryId, request, ifMatch, idempotencyKey);
        return ResponseEntity.accepted()
                .header(HttpHeaders.ETAG, "\"lv:" + value.lockVersion() + "\"")
                .header("Cache-Control", "no-store")
                .body(new SuccessEnvelope<>(value, new ApiMeta(RequestCorrelation.current())));
    }

    private static Boolean parseBoolean(String value) {
        if (value == null) return null;
        if ("true".equalsIgnoreCase(value)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
        throw com.flowdesk.common.web.ApiException.badRequest("INVALID_QUERY_PARAMETER", "deadLettered 必须为 true/false。");
    }
}
