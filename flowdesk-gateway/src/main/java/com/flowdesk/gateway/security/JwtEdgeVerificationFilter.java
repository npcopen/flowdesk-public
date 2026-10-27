package com.flowdesk.gateway.security;

import com.flowdesk.common.security.JwtVerificationException;
import com.flowdesk.common.security.JwtVerifier;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.List;
import java.util.UUID;

/**
 * 网关入口的人类 JWT 首次校验。
 * 目标业务服务仍须本地复验同一令牌；网关不会添加可绕过验签的内部信任头。
 */
@Component
public class JwtEdgeVerificationFilter implements GlobalFilter, Ordered {

    private final JwtVerifier verifier;
    private final Scheduler verificationScheduler;

    /**
     * 注入 JWT 验证器和专用验签调度器，避免后续 RSA 校验占用网关事件循环。
     */
    public JwtEdgeVerificationFilter(JwtVerifier verifier, Scheduler verificationScheduler) {
        this.verifier = verifier;
        this.verificationScheduler = verificationScheduler;
    }

    /**
     * 对受保护的人类 API 提前验签，在独立调度器执行 RSA 校验，失败返回 401，成功继续路由。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!GatewayPathClassifier.requiresHumanJwt(path)) {
            return chain.filter(exchange);
        }
        UUID correlationId = correlationId(exchange);
        String token = bearerToken(exchange);
        if (token == null) {
            return GatewayErrorWriter.write(exchange, HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED", "缺少人类 Bearer 身份。", correlationId);
        }
        // RSA 验签占用 CPU，使用独立调度器以免阻塞 Netty 事件循环。
        Mono<Boolean> verified = Mono.fromCallable(() -> {
            verifier.verifyHuman(token);
            return true;
        }).subscribeOn(verificationScheduler);
        return verified.then(chain.filter(exchange))
                .onErrorResume(JwtVerificationException.class, e -> {
                    String code = e.kind() == JwtVerificationException.Kind.EXPIRED
                            ? "TOKEN_EXPIRED" : "AUTHENTICATION_FAILED";
                    return GatewayErrorWriter.write(exchange, HttpStatus.UNAUTHORIZED,
                            code, "JWT 身份验证失败。", correlationId);
                });
    }

    /**
     * 设置边缘 JWT 检查顺序，确保受保护请求在业务路由前完成认证。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 30;
    }

    /**
     * 仅从唯一 Authorization 头提取 Bearer，重复头或格式不符返回 null。
     */
    private static String bearerToken(ServerWebExchange exchange) {
        List<String> headers = exchange.getRequest().getHeaders().get("Authorization");
        if (headers == null || headers.size() != 1) {
            return null;
        }
        String header = headers.get(0);
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring("Bearer ".length()).trim();
    }

    /**
     * 从请求头读取 UUID，缺失或非法时生成新标识用于认证错误响应。
     */
    private static UUID correlationId(ServerWebExchange exchange) {
        List<String> values = exchange.getRequest().getHeaders().get("X-Correlation-Id");
        if (values != null && values.size() == 1) {
            try {
                return UUID.fromString(values.get(0));
            } catch (IllegalArgumentException ignored) {
                // 格式无效时使用下方新生成的追踪标识。
            }
        }
        return UUID.randomUUID();
    }
}
