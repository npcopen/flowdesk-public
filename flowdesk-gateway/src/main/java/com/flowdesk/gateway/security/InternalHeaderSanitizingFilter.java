package com.flowdesk.gateway.security;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deletes client-forged tenant/actor/internal headers before routing.
 *
 * <p>The rule is deny-by-default: every {@code x-flowdesk-*} header is stripped unless
 * it is a documented webhook security header and the request targets the fixed webhook
 * path. This prevents a future internal header from silently becoming forgeable.</p>
 */
@Component
public class InternalHeaderSanitizingFilter implements GlobalFilter, Ordered {

    private static final Set<String> FORGED_INTERNAL_HEADERS = Set.of(
            "x-tenant-id", "x-tenant", "x-user-id", "x-actor-type", "x-actor-id");
    private static final Set<String> WEBHOOK_SECURITY_HEADERS = Set.of(
            "x-flowdesk-api-key", "x-flowdesk-signature-version", "x-flowdesk-timestamp",
            "x-flowdesk-nonce", "x-flowdesk-signature");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        boolean webhook = GatewayPathClassifier.isWebhook(path);
        ServerHttpRequest mutated = exchange.getRequest().mutate().headers(headers -> {
            FORGED_INTERNAL_HEADERS.forEach(headers::remove);
            java.util.List<String> flowDeskHeaders = new java.util.ArrayList<>();
            headers.forEach((name, values) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.startsWith("x-flowdesk-")
                        && !(webhook && WEBHOOK_SECURITY_HEADERS.contains(lower))) {
                    flowDeskHeaders.add(name);
                }
            });
            flowDeskHeaders.forEach(headers::remove);
        }).build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
