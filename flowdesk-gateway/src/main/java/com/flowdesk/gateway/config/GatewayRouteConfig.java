package com.flowdesk.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * P0 single-target routing table (ARCH-DEC-003/ARCHITECTURE §7.4).
 *
 * <p>Each API path family matches exactly one backend; there is no fallback fan-out or
 * aggregation route. Internal service-to-service paths are intentionally not exposed.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FlowDeskGatewayProperties.class)
public class GatewayRouteConfig {

    /** Caffeine-backed cache for spring-cloud-loadbalancer (avoids the development-only default). */
    @Bean
    public CaffeineCacheManager flowDeskLoadBalancerCacheManager() {
        return new CaffeineCacheManager();
    }

    @Bean
    public RouteLocator flowDeskRouteLocator(RouteLocatorBuilder builder,
                                             FlowDeskGatewayProperties properties) {
        return builder.routes()
                .route("iam-auth-login", r -> r.method(HttpMethod.POST)
                        .and().path("/api/v1/auth/login")
                        .uri(properties.uri("iam")))
                .route("iam-users", r -> r.path(
                                "/api/v1/users/**",
                                "/api/v1/roles/**",
                                "/api/v1/permissions/**")
                        .uri(properties.uri("iam")))
                .route("ticket", r -> r.path(
                                "/api/v1/tickets/**",
                                "/api/v1/service-catalog-items/**",
                                "/api/v1/sla-policies/**",
                                "/api/v1/ticket-message-recoveries/**")
                        .uri(properties.uri("ticket")))
                .route("workflow", r -> r.path(
                                "/api/v1/workflow-definitions/**",
                                "/api/v1/workflow-drafts/**",
                                "/api/v1/workflow-versions/**",
                                "/api/v1/workflow-instances/**",
                                "/api/v1/approval-tasks/**",
                                "/api/v1/workflow-message-recoveries/**")
                        .uri(properties.uri("workflow")))
                .route("alert-webhook", r -> r.path("/api/v1/webhooks/**")
                        .uri(properties.uri("alert")))
                .route("alert", r -> r.path(
                                "/api/v1/alert-events/**",
                                "/api/v1/incidents/**",
                                "/api/v1/alert-message-recoveries/**")
                        .uri(properties.uri("alert")))
                .route("notification", r -> r.path(
                                "/api/v1/notifications/**",
                                "/api/v1/notification-message-recoveries/**")
                        .uri(properties.uri("notification")))
                .build();
    }
}
