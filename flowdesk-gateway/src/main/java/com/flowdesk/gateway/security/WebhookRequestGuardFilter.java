package com.flowdesk.gateway.security;

import com.flowdesk.gateway.config.FlowDeskGatewayProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 在产生业务副作用前校验固定 Webhook 入口的请求协议。
 * 拒绝查询参数、错误方法或媒体类型、浏览器凭据、缺失或重复的安全头、不支持的签名版本及超大请求体。
 * 保留原始请求体字节；HMAC 验签和 nonce/eventId 防重放由告警服务执行。
 */
@Component
public class WebhookRequestGuardFilter implements GlobalFilter, Ordered {

    private static final int WEBHOOK_MAX_BYTES = 64 * 1024;
    private static final Set<String> SECURITY_HEADERS = Set.of(
            "x-flowdesk-api-key", "x-flowdesk-signature-version", "x-flowdesk-timestamp",
            "x-flowdesk-nonce", "x-flowdesk-signature");
    private final FlowDeskGatewayProperties properties;

    /**
     * 注入网关安全配置，使 Webhook 检查能够读取真实 TLS 等部署要求。
     */
    public WebhookRequestGuardFilter(FlowDeskGatewayProperties properties) {
        this.properties = properties;
    }

    /**
     * 按部署开关检查实际 TLS，再校验 POST、无查询参数、UTF-8 JSON、内容编码及唯一的 v1 安全头。
     * 拒绝 Authorization 和 Cookie，并有界缓冲原始正文；签名真实性与防重放仍由告警服务判定。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!GatewayPathClassifier.isWebhook(path)) {
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        if (properties.isWebhookRequireTls() && request.getSslInfo() == null) {
            return error(exchange, HttpStatus.UNAUTHORIZED, "WEBHOOK_AUTHENTICATION_FAILED",
                    "Webhook 必须通过 HTTPS。");
        }
        if (request.getURI().getRawQuery() != null) {
            return error(exchange, HttpStatus.BAD_REQUEST, "WEBHOOK_QUERY_FORBIDDEN",
                    "Webhook 禁止携带 query。");
        }
        if (request.getMethod() != HttpMethod.POST) {
            exchange.getResponse().getHeaders().setAllow(Set.of(HttpMethod.POST));
            return error(exchange, HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                    "Webhook 只接受 POST。");
        }

        List<String> contentTypeValues = request.getHeaders().get(HttpHeaders.CONTENT_TYPE);
        MediaType contentType = contentTypeValues == null ? null
                : request.getHeaders().getContentType();
        if (contentTypeValues == null || contentTypeValues.size() != 1 || contentType == null
                || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                || contentType.getCharset() == null
                || !"UTF-8".equalsIgnoreCase(contentType.getCharset().name())) {
            return error(exchange, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MEDIA_TYPE_UNSUPPORTED",
                    "Webhook Content-Type 必须为 application/json; charset=utf-8。");
        }

        List<String> contentEncodings = request.getHeaders().get(HttpHeaders.CONTENT_ENCODING);
        if (contentEncodings != null
                && (contentEncodings.size() != 1
                        || !"identity".equalsIgnoreCase(contentEncodings.get(0)))) {
            return error(exchange, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "CONTENT_ENCODING_UNSUPPORTED",
                    "Webhook Content-Encoding 只接受 identity。");
        }

        if (request.getHeaders().get(HttpHeaders.AUTHORIZATION) != null
                || request.getHeaders().get(HttpHeaders.COOKIE) != null) {
            return error(exchange, HttpStatus.BAD_REQUEST, "WEBHOOK_HEADER_MISSING",
                    "Webhook 禁止携带 Authorization 或 Cookie。");
        }

        for (String header : SECURITY_HEADERS) {
            List<String> values = request.getHeaders().get(header);
            if (values == null || values.size() != 1
                    || values.get(0) == null || values.get(0).isBlank()) {
                return error(exchange, HttpStatus.BAD_REQUEST, "WEBHOOK_HEADER_MISSING",
                        "Webhook 安全 Header 缺失或重复提交。");
            }
        }
        String version = request.getHeaders().getFirst("x-flowdesk-signature-version");
        if (!"1".equals(version)) {
            return error(exchange, HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_VERSION_UNSUPPORTED",
                    "Webhook 签名版本不支持。");
        }

        long contentLength = request.getHeaders().getContentLength();
        if (contentLength > WEBHOOK_MAX_BYTES) {
            return error(exchange, HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_TOO_LARGE",
                    "Webhook body 超过 64 KiB。");
        }
        return DataBufferUtils.join(request.getBody(), WEBHOOK_MAX_BYTES + 1)
                .switchIfEmpty(Mono.fromSupplier(() ->
                        exchange.getResponse().bufferFactory().allocateBuffer(0)))
                .flatMap(joined -> {
                    Flux<DataBuffer> body = joined.readableByteCount() == 0
                            ? Flux.empty()
                            : Flux.just(joined);
                    ServerHttpRequest guarded = buffered(request, body);
                    return chain.filter(exchange.mutate().request(guarded).build());
                })
                .onErrorResume(DataBufferLimitException.class, e ->
                        error(exchange, HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_TOO_LARGE",
                                "Webhook body 超过 64 KiB。"));
    }

    /**
     * 设置 Webhook 协议检查顺序，在转发告警服务前拒绝非法请求。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 15;
    }

    /**
     * 用已缓冲的原始字节流装饰请求，使下游验签与网关检查读取相同载荷。
     */
    private static ServerHttpRequest buffered(ServerHttpRequest request, Flux<DataBuffer> body) {
        return new ServerHttpRequestDecorator(request) {
            /**
             * 返回已缓冲的原始正文流，确保下游验签读取与网关检查相同的字节。
             */
            @Override
            public Flux<DataBuffer> getBody() {
                return body;
            }
        };
    }

    /**
     * 提取或补齐关联 UUID，并委托统一网关错误写入器返回协议错误。
     */
    private static Mono<Void> error(ServerWebExchange exchange,
                                    HttpStatus status,
                                    String code,
                                    String message) {
        List<String> values = exchange.getRequest().getHeaders().get("X-Correlation-Id");
        UUID correlationId = null;
        if (values != null && values.size() == 1) {
            try {
                correlationId = UUID.fromString(values.get(0));
            } catch (IllegalArgumentException ignored) {
                correlationId = null;
            }
        }
        if (correlationId == null) {
            correlationId = UUID.randomUUID();
        }
        return GatewayErrorWriter.write(exchange, status, code, message, correlationId);
    }
}
