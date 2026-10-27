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
 * 规范化网关公开入口的追踪标识。
 * 仅接受单个有效 UUID；缺失、无效或重复时重新生成。关联标识仅用于追踪，不参与授权。
 */
@Component
public class CorrelationIdWebFilter implements GlobalFilter, Ordered {

    /**
     * 保留唯一合法 UUID，否则生成新标识，同时更新下游请求头和响应头，保证链路标识一致。
     */
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

    /**
     * 设置关联标识过滤器顺序，使后续认证及错误响应读取规范化标识。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    /**
     * 检查请求头长度和 UUID 格式，解析失败返回 false。
     */
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
