package com.flowdesk.iam.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional local/bootstrap service-JWT issuance configuration.
 *
 * <p>Disabled by default. When enabled, {@code mappings} entries use the form
 * {@code tenantId:serviceId}. The default preserves the one-shot bootstrap mode;
 * local long-running IAM processes can disable {@code exit-after-issue} and rotate
 * the files before the short-lived service JWTs expire. Tokens are never logged.</p>
 */
@ConfigurationProperties(prefix = "flowdesk.iam.service-jwt-bootstrap")
public class ServiceJwtBootstrapProperties {

    private boolean enabled = false;
    private boolean exitAfterIssue = true;
    private Duration refreshInterval = Duration.ofMinutes(2);
    private String outputDirectory = ".data/service-tokens";
    private List<String> mappings = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isExitAfterIssue() {
        return exitAfterIssue;
    }

    public void setExitAfterIssue(boolean exitAfterIssue) {
        this.exitAfterIssue = exitAfterIssue;
    }

    public Duration getRefreshInterval() {
        return refreshInterval;
    }

    public void setRefreshInterval(Duration refreshInterval) {
        if (refreshInterval == null || refreshInterval.isZero() || refreshInterval.isNegative()) {
            throw new IllegalArgumentException("service-jwt-bootstrap refresh-interval must be positive");
        }
        this.refreshInterval = refreshInterval;
    }

    public String getOutputDirectory() {
        return outputDirectory;
    }

    public void setOutputDirectory(String outputDirectory) {
        if (outputDirectory == null || outputDirectory.isBlank()) {
            throw new IllegalArgumentException("service-jwt-bootstrap output-directory must not be blank");
        }
        this.outputDirectory = outputDirectory;
    }

    public List<String> getMappings() {
        return mappings;
    }

    public void setMappings(List<String> mappings) {
        this.mappings = mappings == null ? new ArrayList<>() : new ArrayList<>(mappings);
    }
}
