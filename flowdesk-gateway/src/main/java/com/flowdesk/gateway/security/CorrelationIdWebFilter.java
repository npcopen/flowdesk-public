package com.flowdesk.gateway.security;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Gateway owns the public correlation contract: one valid UUID is accepted, anything
 * missing/invalid/duplicated is replaced with a new value. Correlation never carries
 * authorization semantics (API-SPEC §2.4).
 */
@Component
public class CorrelationIdWebFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        List<String> values = exchange.getRequest().getHeaders().get("X-Correlation-Id");
        UUID selected = null;
        if (values != null && values.size() == 1 && isUuid(values.get(0))) {
            selected = UUID.fromString(values.get(0));
        } else {
            selected = UUID.randomUUID();
        }
        final UUID correlationId = selected;
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(headers -> headers.set("X-Correlation-Id", correlationId.toString()))
                .build();
        ServerWebExchange mutatedExchange = exchange.mutate().request(mutated).build();
        mutatedExchange.getResponse().getHeaders().set("X-Correlation-Id", correlationId.toString());
        return chain.filter(mutatedExchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    private static boolean isUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
