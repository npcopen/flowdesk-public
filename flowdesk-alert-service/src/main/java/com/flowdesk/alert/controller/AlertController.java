package com.flowdesk.alert.controller;
import com.flowdesk.alert.dto.*;

import com.flowdesk.alert.service.AlertQueryService;
import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.web.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.UUID;

/**
 * 面向已登录用户的告警查询 HTTP 入口。
 *
 * <p>事件接口展示每次外部告警的接纳事实，故障接口展示多次告警聚合后的周期状态，
 * ticket-link 接口展示该故障异步建单的进度。接口权限由 RequiresPermission 声明，
 * 参数格式由 AlertQueryParams 校验，筛选与分页语义交给 AlertQueryService。
 */
@RestController
public class AlertController {
    private static final Set<String> EVENT_PARAMS =
            Set.of(
                    "eventType",
                    "disposition",
                    "incidentId",
                    "fingerprint",
                    "cycleRef",
                    "externalEventId",
                    "receivedFrom",
                    "receivedTo",
                    "pageSize",
                    "pageToken",
                    "sort");
    private static final Set<String> INCIDENT_PARAMS =
            Set.of(
                    "status",
                    "fingerprint",
                    "ticketLinkStatus",
                    "openedFrom",
                    "openedTo",
                    "pageSize",
                    "pageToken",
                    "sort");
    private final AlertQueryService service;

    /**
     * 注入当前 HTTP 入口所需的业务用例，使请求校验及响应包装调用同一服务能力。
     */
    public AlertController(AlertQueryService service) {
        this.service = service;
    }

    /**
     * 校验筛选条件与排序后分页查询当前租户的告警事件，返回响应视图和绑定本次条件的下一页游标。
     */
    @GetMapping("/api/v1/alert-events")
    @RequiresPermission(PermissionCode.ALERT_EVENT_READ_TENANT)
    public SuccessEnvelope<java.util.List<AlertDtos.AlertEventView>> events(
            @RequestParam MultiValueMap<String, String> p) {
        AlertQueryParams.validate(p, EVENT_PARAMS);
        int size = AlertQueryParams.pageSize(p.getFirst("pageSize"));
        var page =
                service.events(
                        new com.flowdesk.alert.model.EventFilter(
                                p.getFirst("eventType"),
                                p.getFirst("disposition"),
                                AlertQueryParams.uuid(p.getFirst("incidentId")),
                                p.getFirst("fingerprint"),
                                p.getFirst("cycleRef"),
                                p.getFirst("externalEventId"),
                                AlertQueryParams.instant(p.getFirst("receivedFrom")),
                                AlertQueryParams.instant(p.getFirst("receivedTo"))),
                        p.getFirst("sort"),
                        size,
                        p.getFirst("pageToken"));
        return new SuccessEnvelope<>(
                page.items(),
                new ApiMeta(
                        RequestCorrelation.current(),
                        new PageMeta(page.items().size(), page.next(), page.sort())));
    }

    /**
     * 委托查询服务读取当前租户事件详情，并包装统一成功响应及请求关联信息。
     */
    @GetMapping("/api/v1/alert-events/{id}")
    @RequiresPermission(PermissionCode.ALERT_EVENT_READ_TENANT)
    public SuccessEnvelope<AlertDtos.AlertEventView> event(
            @PathVariable UUID id, @RequestParam MultiValueMap<String, String> p) {
        AlertQueryParams.validate(p, Set.of());
        return new SuccessEnvelope<>(service.event(id), new ApiMeta(RequestCorrelation.current()));
    }

    /**
     * 分页查询当前租户的故障周期，校验时间范围、业务状态和排序，并返回签名续查游标。
     */
    @GetMapping("/api/v1/incidents")
    @RequiresPermission(PermissionCode.INCIDENT_READ_TENANT)
    public SuccessEnvelope<java.util.List<AlertDtos.IncidentView>> incidents(
            @RequestParam MultiValueMap<String, String> p) {
        AlertQueryParams.validate(p, INCIDENT_PARAMS);
        int size = AlertQueryParams.pageSize(p.getFirst("pageSize"));
        var page =
                service.incidents(
                        new com.flowdesk.alert.model.IncidentFilter(
                                p.getFirst("status"),
                                p.getFirst("fingerprint"),
                                p.getFirst("ticketLinkStatus"),
                                AlertQueryParams.instant(p.getFirst("openedFrom")),
                                AlertQueryParams.instant(p.getFirst("openedTo"))),
                        p.getFirst("sort"),
                        size,
                        p.getFirst("pageToken"));
        return new SuccessEnvelope<>(
                page.items(),
                new ApiMeta(
                        RequestCorrelation.current(),
                        new PageMeta(page.items().size(), page.next(), page.sort())));
    }

    /**
     * 统计当前租户的活动故障数量，供概览展示，避免把其他租户数据计入结果。
     */
    @GetMapping("/api/v1/incidents/summary")
    @RequiresPermission(PermissionCode.INCIDENT_READ_TENANT)
    public SuccessEnvelope<java.util.Map<String, Long>> incidentSummary(
            @RequestParam MultiValueMap<String, String> p) {
        AlertQueryParams.validate(p, Set.of());
        return new SuccessEnvelope<>(
                service.incidentSummary(), new ApiMeta(RequestCorrelation.current()));
    }

    /** 详情同时返回 lockVersion 对应的 ETag，方便调用方识别当前读取的状态版本。 */
    @GetMapping("/api/v1/incidents/{id}")
    @RequiresPermission(PermissionCode.INCIDENT_READ_TENANT)
    public ResponseEntity<SuccessEnvelope<AlertDtos.IncidentView>> incident(
            @PathVariable UUID id, @RequestParam MultiValueMap<String, String> p) {
        AlertQueryParams.validate(p, Set.of());
        var value = service.incident(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, "\"lv:" + value.lockVersion() + "\"")
                .body(new SuccessEnvelope<>(value, new ApiMeta(RequestCorrelation.current())));
    }

    /**
     * 确认故障对当前租户可见后读取工单关联进度；仅由恢复消息建立的周期返回明确的 NOT_REQUIRED。
     */
    @GetMapping("/api/v1/incidents/{id}/ticket-link")
    @RequiresPermission(PermissionCode.INCIDENT_READ_TENANT)
    public ResponseEntity<SuccessEnvelope<AlertDtos.IncidentTicketLinkView>> link(
            @PathVariable UUID id, @RequestParam MultiValueMap<String, String> p) {
        AlertQueryParams.validate(p, Set.of());
        var value = service.link(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, "\"lv:" + value.lockVersion() + "\"")
                .body(new SuccessEnvelope<>(value, new ApiMeta(RequestCorrelation.current())));
    }
}
