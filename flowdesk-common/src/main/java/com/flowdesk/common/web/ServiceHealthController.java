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
 * Servlet 业务服务共用的存活与就绪探针。
 * 存活检查不访问远端依赖；就绪检查只验证本服务数据库及迁移记录，避免报告未经验证的依赖状态。
 */
@RestController
@RequestMapping("/api/v1/internal/health")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(ServiceHealthProperties.class)
public class ServiceHealthController {

    private final org.springframework.beans.factory.ObjectProvider<DataSource> dataSource;
    private final ServiceHealthProperties properties;
    private final Clock clock;

    /**
     * 注入可选数据源提供器及探针配置，使用 UTC 时钟记录探针时间。
     */
    public ServiceHealthController(
            org.springframework.beans.factory.ObjectProvider<DataSource> dataSource,
            ServiceHealthProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.clock = Clock.systemUTC();
    }

    /**
     * 返回进程存活探针及关联标识，不访问数据库，避免依赖故障触发无意义重启。
     */
    @GetMapping("/liveness")
    public ResponseEntity<SuccessEnvelope<ProbeStatus>> liveness(HttpServletRequest request) {
        return success(ProbeStatus.Probe.LIVENESS, ProbeStatus.Status.UP,
                RequestCorrelation.currentOrNull(request));
    }

    /**
     * 按配置检查数据库及迁移状态，未就绪返回可重试 503，成功则返回就绪探针。
     */
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

    /**
     * 用统一成功信封包装探针，设置 no-store 及关联头，避免健康状态被缓存。
     */
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

    /**
     * 未要求数据库时直接就绪；否则验证连接和 SELECT 1，并按配置检查至少存在一条成功迁移记录。
     * 此处不比对全部待执行迁移；数据源缺失、检查失败或异常均按未就绪处理。
     */
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
            // 探针仅返回是否就绪，避免泄露 SQL、主机、用户名或依赖连接细节。
            return false;
        }
    }
}
