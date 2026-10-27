package com.flowdesk.gateway.web;

import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.ProbeStatus;
import com.flowdesk.common.web.SuccessEnvelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;
/**
 * 响应式网关的存活与就绪探针，对应 API-063/API-064。
 */
@RestController
@RequestMapping("/api/v1/internal/health")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class GatewayHealthController {

    /**
     * 返回网关存活探针，并规范化请求中的关联标识。
     */
    @GetMapping("/liveness")
    public ResponseEntity<SuccessEnvelope<ProbeStatus>> liveness(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationHeader) {
        return success(ProbeStatus.Probe.LIVENESS, ProbeStatus.Status.UP, correlation(correlationHeader));
    }

    /**
     * 返回 API 网关就绪探针，前端容器资源不参与此就绪判断。
     */
    @GetMapping("/readiness")
    public ResponseEntity<SuccessEnvelope<ProbeStatus>> readiness(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationHeader) {
        // Frontend availability belongs to its own container, not API readiness.
        return success(ProbeStatus.Probe.READINESS, ProbeStatus.Status.UP, correlation(correlationHeader));
    }

    /**
     * 构建不缓存的探针响应，保证信封与响应头使用同一关联 UUID。
     */
    private ResponseEntity<SuccessEnvelope<ProbeStatus>> success(
            ProbeStatus.Probe probe, ProbeStatus.Status status, UUID correlation) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Correlation-Id", correlation.toString())
                .body(new SuccessEnvelope<>(
                        new ProbeStatus(probe, status, Instant.now()), new ApiMeta(correlation)));
    }

    /**
     * 保留合法 UUID 请求头，缺失或格式错误则生成新追踪标识。
     */
    private static UUID correlation(String value) {
        if (value != null) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException ignored) {
                // The Gateway correlation filter normally normalizes this first.
            }
        }
        return UUID.randomUUID();
    }
}
