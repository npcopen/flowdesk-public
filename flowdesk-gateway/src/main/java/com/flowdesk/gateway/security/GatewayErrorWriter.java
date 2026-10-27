package com.flowdesk.gateway.security;

import tools.jackson.databind.ObjectMapper;
import com.flowdesk.common.web.ApiError;
import com.flowdesk.common.web.ErrorEnvelope;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 生成网关统一错误响应，避免暴露内部拓扑或密钥。
 */
public final class GatewayErrorWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 禁止直接实例化该工具类型，统一通过静态方法或工厂入口使用。
     */
    private GatewayErrorWriter() {
    }

    /**
     * 响应式写入 JSON 错误信封、状态码和关联头，序列化失败时仍输出最小合法错误 JSON。
     */
    public static Mono<Void> write(ServerWebExchange exchange,
                                   HttpStatus status,
                                   String code,
                                   String message,
                                   UUID correlationId) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set("X-Correlation-Id", correlationId.toString());
        ErrorEnvelope envelope = new ErrorEnvelope(
                new ApiError(code, message, correlationId, false, null));
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(envelope);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            byte[] bytes = ("{\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"gateway error\","
                    + "\"correlationId\":\"" + correlationId + "\",\"retryable\":false}}")
                    .getBytes(StandardCharsets.UTF_8);
            return exchange.getResponse().writeWith(Mono.just(
                    exchange.getResponse().bufferFactory().wrap(bytes)));
        }
    }
}
