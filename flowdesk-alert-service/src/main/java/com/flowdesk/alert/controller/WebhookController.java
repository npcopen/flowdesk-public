package com.flowdesk.alert.controller;
import com.flowdesk.alert.dto.*;

import com.flowdesk.alert.service.WebhookAcceptanceService;
import com.flowdesk.alert.webhook.WebhookAlertPayload;
import com.flowdesk.alert.webhook.WebhookAuthenticationFilter;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.RequestCorrelation;
import com.flowdesk.common.web.SuccessEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 外部监控系统的告警接收入口，与用户 JWT 查询接口使用不同的认证路径。
 *
 * <p>WebhookAuthenticationFilter 已校验请求签名，并保存原始字节与已验签身份；
 * 此处只从这些字节解析报文，再交给 WebhookAcceptanceService 完成本地事务。
 * 返回 202 表示本地已接纳，工单创建或时间线更新的异步进度包含在回执中。
 */
@RestController
@ConditionalOnProperty(prefix = "flowdesk.alert.webhook", name = "enabled", havingValue = "true")
public class WebhookController {
    private final WebhookAcceptanceService service;

    /**
     * 注入当前 HTTP 入口所需的业务用例，使请求校验及响应包装调用同一服务能力。
     */
    public WebhookController(WebhookAcceptanceService service) {
        this.service = service;
    }

    /**
     * 从请求属性取出已验签快照并解析业务载荷，再交由接纳服务登记事件、周期与异步意图。
     */
    @PostMapping(
            path = "/api/v1/webhooks/monitoring/alerts",
            consumes = "application/json",
            produces = "application/json")
    public ResponseEntity<SuccessEnvelope<AlertDtos.WebhookReceipt>> accept(
            HttpServletRequest request) {
        var verified = WebhookAuthenticationFilter.verified(request);
        // 必须解析验签时使用的原文，避免重新读取或序列化请求后改变被验证的内容。
        final WebhookAlertPayload payload;
        try {
            payload = WebhookAlertPayload.parse(verified.rawBody());
        } catch (WebhookAlertPayload.InvalidPayload invalid) {
            throw ApiException.unprocessable(
                    "WEBHOOK_BODY_INVALID", "Webhook body 无效。", java.util.List.of());
        }
        var receipt = service.accept(verified, payload);
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(new SuccessEnvelope<>(receipt, new ApiMeta(RequestCorrelation.current())));
    }
}
