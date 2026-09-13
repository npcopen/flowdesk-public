package com.flowdesk.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Single-target route URIs. Defaults use Nacos discovery (lb://); local integration
 * tests override them with direct localhost URIs.
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

    public Map<String, String> getRoutes() {
        return routes;
    }

    public boolean isWebhookRequireTls() { return webhookRequireTls; }
    public void setWebhookRequireTls(boolean webhookRequireTls) {
        this.webhookRequireTls = webhookRequireTls;
    }

    public String uri(String service) {
        String uri = routes.get(service);
        if (uri == null || uri.isBlank()) {
            throw new IllegalStateException("Gateway route URI is not configured for " + service);
        }
        return uri;
    }
}
