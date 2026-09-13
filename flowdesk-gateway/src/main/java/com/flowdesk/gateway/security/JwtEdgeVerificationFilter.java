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
 * Gateway edge JWT first check (ARCH-DEC-005). Target services still re-verify the same
 * token locally; this filter never adds any internal trust header.
 */
@Component
public class JwtEdgeVerificationFilter implements GlobalFilter, Ordered {

    private final JwtVerifier verifier;
    private final Scheduler verificationScheduler;

    public JwtEdgeVerificationFilter(JwtVerifier verifier, Scheduler verificationScheduler) {
        this.verifier = verifier;
        this.verificationScheduler = verificationScheduler;
    }

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
        // RSA verification is CPU-bound and must not run on the Netty event loop.
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

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 30;
    }

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

    private static UUID correlationId(ServerWebExchange exchange) {
        List<String> values = exchange.getRequest().getHeaders().get("X-Correlation-Id");
        if (values != null && values.size() == 1) {
            try {
                return UUID.fromString(values.get(0));
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return UUID.randomUUID();
    }
}
