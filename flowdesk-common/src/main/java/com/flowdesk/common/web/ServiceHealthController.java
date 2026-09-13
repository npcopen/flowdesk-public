package com.flowdesk.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * Shared API-063/API-064 implementation for the servlet fact services.
 *
 * <p>The endpoint intentionally performs only local checks. Liveness never touches a
 * remote dependency. Readiness checks the service-owned database and migration table;
 * optional Redis/MQ failures are represented by the service as DEGRADED through a
 * future contributor rather than making this probe claim details it cannot prove.</p>
 */
@RestController
@RequestMapping("/api/v1/internal/health")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(ServiceHealthProperties.class)
public class ServiceHealthController {

    private final org.springframework.beans.factory.ObjectProvider<DataSource> dataSource;
    private final ServiceHealthProperties properties;
    private final Clock clock;

    public ServiceHealthController(
            org.springframework.beans.factory.ObjectProvider<DataSource> dataSource,
            ServiceHealthProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.clock = Clock.systemUTC();
    }

    @GetMapping("/liveness")
    public ResponseEntity<SuccessEnvelope<ProbeStatus>> liveness(HttpServletRequest request) {
        return success(ProbeStatus.Probe.LIVENESS, ProbeStatus.Status.UP,
                RequestCorrelation.currentOrNull(request));
    }

    @GetMapping("/readiness")
    public ResponseEntity<SuccessEnvelope<ProbeStatus>> readiness(HttpServletRequest request) {
        if (!isReady()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_NOT_READY",
                    "当前服务尚未准备好接收流量。", true, java.util.List.of(),
                    Map.of("Cache-Control", "no-store"));
        }
        return success(ProbeStatus.Probe.READINESS, ProbeStatus.Status.UP,
                RequestCorrelation.currentOrNull(request));
    }

    private ResponseEntity<SuccessEnvelope<ProbeStatus>> success(
            ProbeStatus.Probe probe, ProbeStatus.Status status, java.util.UUID requestCorrelation) {
        java.util.UUID correlation = requestCorrelation == null
                ? java.util.UUID.randomUUID() : requestCorrelation;
        ProbeStatus payload = new ProbeStatus(probe, status, Instant.now(clock));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Correlation-Id", correlation.toString())
                .body(new SuccessEnvelope<>(payload, new ApiMeta(correlation)));
    }

    private boolean isReady() {
        if (!properties.isDatabaseRequired()) {
            return true;
        }
        try {
            DataSource source = dataSource.getIfAvailable();
            if (source == null) {
                return false;
            }
            try (Connection connection = source.getConnection()) {
                if (!connection.isValid(2)) {
                    return false;
                }
                try (PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next() || result.getInt(1) != 1) {
                            return false;
                        }
                    }
                }
                if (properties.isMigrationRequired()) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT 1 FROM flyway_schema_history WHERE success = 1 LIMIT 1")) {
                        try (ResultSet result = statement.executeQuery()) {
                            return result.next();
                        }
                    }
                }
                return true;
            }
        } catch (Exception ignored) {
            // Do not expose SQL, host, username or dependency details to probes.
            return false;
        }
    }
}
