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
 * 路由前删除客户端伪造的租户、操作者和内部请求头。
 * 默认移除全部 x-flowdesk-* 请求头，仅在固定 Webhook 路径保留契约允许的安全头，防止新增内部头被伪造。
 */
@Component
public class InternalHeaderSanitizingFilter implements GlobalFilter, Ordered {

    private static final Set<String> FORGED_INTERNAL_HEADERS = Set.of(
            "x-tenant-id", "x-tenant", "x-user-id", "x-actor-type", "x-actor-id");
    private static final Set<String> WEBHOOK_SECURITY_HEADERS = Set.of(
            "x-flowdesk-api-key", "x-flowdesk-signature-version", "x-flowdesk-timestamp",
            "x-flowdesk-nonce", "x-flowdesk-signature");

    /**
     * 移除客户端伪造的内部身份及 FlowDesk 头，Webhook 只保留安全协议白名单中的头，再转发下游。
     */
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

    /**
     * 设置内部头清理顺序，保证伪造身份信息在路由到后端前移除。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
