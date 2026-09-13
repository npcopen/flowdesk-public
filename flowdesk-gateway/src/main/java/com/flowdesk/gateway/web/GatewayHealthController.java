package com.flowdesk.gateway.web;

import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.ErrorEnvelope;
import com.flowdesk.common.web.ApiError;
import com.flowdesk.common.web.ProbeStatus;
import com.flowdesk.common.web.SuccessEnvelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/** API-063/API-064 for the reactive Gateway listener. */
@RestController
@RequestMapping("/api/v1/internal/health")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class GatewayHealthController {

    private static final Pattern APP_MOUNT = Pattern.compile(
            "<div\\s+[^>]*\\bid\\s*=\\s*[\\\"']app[\\\"'][^>]*>[^<]*</div>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSET_REFERENCE = Pattern.compile(
            "(?:src|href)\\s*=\\s*[\\\"']/assets/([^\\\"'?#\\s]+)[\\\"']",
            Pattern.CASE_INSENSITIVE);

    private final boolean staticBundleRequired;

    public GatewayHealthController(
            @Value("${flowdesk.health.static-bundle-required:true}") boolean staticBundleRequired) {
        this.staticBundleRequired = staticBundleRequired;
    }

    @GetMapping("/liveness")
    public ResponseEntity<SuccessEnvelope<ProbeStatus>> liveness(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationHeader) {
        return success(ProbeStatus.Probe.LIVENESS, ProbeStatus.Status.UP, correlation(correlationHeader));
    }

    @GetMapping("/readiness")
    public ResponseEntity<?> readiness(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationHeader) {
        UUID correlation = correlation(correlationHeader);
        if (!staticBundleReady()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .header("X-Correlation-Id", correlation.toString())
                    .body(new ErrorEnvelope(new ApiError(
                            "SERVICE_NOT_READY", "当前服务尚未准备好接收流量。", correlation, true, null)));
        }
        return success(ProbeStatus.Probe.READINESS, ProbeStatus.Status.UP, correlation);
    }

    private ResponseEntity<SuccessEnvelope<ProbeStatus>> success(
            ProbeStatus.Probe probe, ProbeStatus.Status status, UUID correlation) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Correlation-Id", correlation.toString())
                .body(new SuccessEnvelope<>(
                        new ProbeStatus(probe, status, Instant.now()), new ApiMeta(correlation)));
    }

    private boolean staticBundleReady() {
        ClassPathResource index = new ClassPathResource("static/index.html");
        if (!staticBundleRequired) {
            return true;
        }
        if (!index.exists() || !index.isReadable()) {
            return false;
        }
        try (InputStream stream = index.getInputStream()) {
            String html = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            if (html.isBlank() || !APP_MOUNT.matcher(html).find()) {
                return false;
            }
            Matcher references = ASSET_REFERENCE.matcher(html);
            boolean assetFound = false;
            while (references.find()) {
                assetFound = true;
                String assetName = references.group(1);
                // Vite emits flat asset names. Reject traversal and nested paths
                // before constructing a classpath resource.
                if (assetName.contains("/") || assetName.contains("\\") || assetName.contains("..")) {
                    return false;
                }
                ClassPathResource asset = new ClassPathResource("static/assets/" + assetName);
                if (!asset.exists() || !asset.isReadable() || asset.contentLength() == 0) {
                    return false;
                }
            }
            return assetFound;
        } catch (Exception ignored) {
            // Keep probe responses opaque and fail closed on a malformed bundle.
            return false;
        }
    }

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
