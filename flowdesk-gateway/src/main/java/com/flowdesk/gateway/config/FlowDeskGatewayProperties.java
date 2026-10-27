package com.flowdesk.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 各业务 API 的单目标路由地址。
 * 默认通过 Nacos 服务发现（lb://）寻址，集成测试可覆盖为本机直连地址。
 */
@ConfigurationProperties(prefix = "flowdesk.gateway")
public class FlowDeskGatewayProperties {

    private boolean webhookRequireTls = true;

    private final Map<String, String> routes = new HashMap<>(Map.of(
            "iam", "lb://flowdesk-iam-service",
            "ticket", "lb://flowdesk-ticket-service",
            "workflow", "lb://flowdesk-workflow-service",
            "alert", "lb://flowdesk-alert-service",
            "notification", "lb://flowdesk-notification-service"
    ));

    /**
     * 返回服务名与路由 URI 的映射，供配置绑定和运行时组件初始化使用。
     */
    public Map<String, String> getRoutes() {
        return routes;
    }

    /**
     * 返回Webhook 的真实 TLS 要求，供配置绑定和运行时组件初始化使用。
     */
    public boolean isWebhookRequireTls() { return webhookRequireTls; }
    /**
     * 设置Webhook 的真实 TLS 要求，供配置绑定和运行时组件初始化使用。
     */
    public void setWebhookRequireTls(boolean webhookRequireTls) {
        this.webhookRequireTls = webhookRequireTls;
    }

    /**
     * 按服务名获取路由目标，未配置或为空时直接失败，避免将请求转发到错误后端。
     */
    public String uri(String service) {
        String uri = routes.get(service);
        if (uri == null || uri.isBlank()) {
            throw new IllegalStateException("Gateway route URI is not configured for " + service);
        }
        return uri;
    }
}
