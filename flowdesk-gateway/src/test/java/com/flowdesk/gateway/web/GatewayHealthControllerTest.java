package com.flowdesk.gateway.web;

import com.flowdesk.common.web.ProbeStatus;
import com.flowdesk.common.web.ErrorEnvelope;
import com.flowdesk.common.web.SuccessEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Focused unit coverage for the Gateway's dependency-free health endpoints. */
class GatewayHealthControllerTest {

    private static final UUID CORRELATION =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void livenessReturnsUpWithTheNormalizedCorrelationId() {
        GatewayHealthController controller = new GatewayHealthController(true);

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

    @Test
    void malformedCorrelationIsReplacedWithAnOpaqueUuid() {
        GatewayHealthController controller = new GatewayHealthController(false);

        ResponseEntity<SuccessEnvelope<ProbeStatus>> response =
                controller.liveness("not-a-uuid");

        String header = response.getHeaders().getFirst("X-Correlation-Id");
        assertThat(header).isNotBlank();
        assertThat(UUID.fromString(header)).isEqualTo(response.getBody().meta().correlationId());
    }

    @Test
    void readinessVerifiesTheBundledSpaAndReturnsTheReadinessSchema() {
        // The test resources provide a minimal same-origin index.html with an app
        // mount; the production artifact verifier independently checks hashed assets.
        GatewayHealthController controller = new GatewayHealthController(true);

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

    @Test
    void readinessFailsClosedWhenIndexHasNoApplicationAsset(@TempDir Path classpath) throws Exception {
        Path staticDirectory = Files.createDirectories(classpath.resolve("static"));
        Files.writeString(staticDirectory.resolve("index.html"),
                "<!doctype html><html><body><div id=\"app\"></div></body></html>");
        ClassLoader original = Thread.currentThread().getContextClassLoader();

        try (URLClassLoader isolated = new URLClassLoader(
                new java.net.URL[]{classpath.toUri().toURL()}, null)) {
            Thread.currentThread().setContextClassLoader(isolated);
            GatewayHealthController controller = new GatewayHealthController(true);

            ResponseEntity<?> response = controller.readiness(CORRELATION.toString());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(response.getHeaders().getFirst("X-Correlation-Id"))
                    .isEqualTo(CORRELATION.toString());
            assertThat(response.getBody()).isInstanceOf(ErrorEnvelope.class);
            ErrorEnvelope envelope = (ErrorEnvelope) response.getBody();
            assertThat(envelope.error().code()).isEqualTo("SERVICE_NOT_READY");
            assertThat(envelope.error().message()).isEqualTo("当前服务尚未准备好接收流量。");
            assertThat(envelope.toString()).doesNotContain(classpath.toString(), "static/index.html");
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void readinessCanBeDisabledForMinimalDiagnosticArtifacts() {
        GatewayHealthController controller = new GatewayHealthController(false);

        ResponseEntity<?> response = controller.readiness(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(SuccessEnvelope.class);
        SuccessEnvelope<?> envelope = (SuccessEnvelope<?>) response.getBody();
        assertThat(envelope.data()).isInstanceOf(ProbeStatus.class);
        assertThat(((ProbeStatus) envelope.data()).status()).isEqualTo(ProbeStatus.Status.UP);
    }
}
