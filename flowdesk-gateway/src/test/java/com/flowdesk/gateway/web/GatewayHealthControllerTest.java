package com.flowdesk.gateway.web;

import com.flowdesk.common.web.ProbeStatus;
import com.flowdesk.common.web.SuccessEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证网关存活与就绪探针的统一响应、关联标识规范化，以及与前端资源解耦的部署职责。
 */
class GatewayHealthControllerTest {

    private static final UUID CORRELATION =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    /**
     * 验证网关存活探针返回 UP 并在响应中保留规范化关联标识。
     */
    @Test
    void livenessReturnsUpWithTheNormalizedCorrelationId() {
        GatewayHealthController controller = new GatewayHealthController();

        ResponseEntity<SuccessEnvelope<ProbeStatus>> response =
                controller.liveness(CORRELATION.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("X-Correlation-Id"))
                .isEqualTo(CORRELATION.toString());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().probe()).isEqualTo(ProbeStatus.Probe.LIVENESS);
        assertThat(response.getBody().data().status()).isEqualTo(ProbeStatus.Status.UP);
        assertThat(response.getBody().meta().correlationId()).isEqualTo(CORRELATION);
    }

    /**
     * 验证非法关联标识被替换为不透明 UUID，避免未校验内容进入响应和追踪链。
     */
    @Test
    void malformedCorrelationIsReplacedWithAnOpaqueUuid() {
        GatewayHealthController controller = new GatewayHealthController();

        ResponseEntity<SuccessEnvelope<ProbeStatus>> response =
                controller.liveness("not-a-uuid");

        String header = response.getHeaders().getFirst("X-Correlation-Id");
        assertThat(header).isNotBlank();
        assertThat(UUID.fromString(header)).isEqualTo(response.getBody().meta().correlationId());
    }

    /**
     * 验证网关就绪探针返回约定 API 就绪模式及统一元数据。
     */
    @Test
    void readinessReturnsTheApiReadinessSchema() {
        GatewayHealthController controller = new GatewayHealthController();

        ResponseEntity<?> response = controller.readiness(CORRELATION.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("X-Correlation-Id"))
                .isEqualTo(CORRELATION.toString());
        assertThat(response.getBody()).isInstanceOf(SuccessEnvelope.class);
        SuccessEnvelope<?> envelope = (SuccessEnvelope<?>) response.getBody();
        assertThat(envelope.data()).isInstanceOf(ProbeStatus.class);
        ProbeStatus status = (ProbeStatus) envelope.data();
        assertThat(status.probe()).isEqualTo(ProbeStatus.Probe.READINESS);
        assertThat(status.status()).isEqualTo(ProbeStatus.Status.UP);
        assertThat(envelope.meta().correlationId()).isEqualTo(CORRELATION);
    }

    /**
     * 验证网关就绪状态不依赖前端静态资源，符合独立 API 网关部署职责。
     */
    @Test
    void readinessDoesNotDependOnFrontendResources(@TempDir Path emptyClasspath) throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader isolated = new URLClassLoader(
                new java.net.URL[]{emptyClasspath.toUri().toURL()}, null)) {
            Thread.currentThread().setContextClassLoader(isolated);

            ResponseEntity<SuccessEnvelope<ProbeStatus>> response =
                    new GatewayHealthController().readiness(null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().data().probe()).isEqualTo(ProbeStatus.Probe.READINESS);
            assertThat(response.getBody().data().status()).isEqualTo(ProbeStatus.Status.UP);
            assertThat(response.getBody().meta().correlationId()).isNotNull();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }
}
