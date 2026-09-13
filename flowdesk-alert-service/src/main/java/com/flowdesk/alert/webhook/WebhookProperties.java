package com.flowdesk.alert.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "flowdesk.alert.webhook")
public class WebhookProperties {
    private boolean enabled;
    private Duration timestampWindow = Duration.ZERO;
    private String secretsFile = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getTimestampWindow() { return timestampWindow; }
    public void setTimestampWindow(Duration timestampWindow) { this.timestampWindow = timestampWindow; }
    public String getSecretsFile() { return secretsFile; }
    public void setSecretsFile(String secretsFile) { this.secretsFile = secretsFile; }

    void validateEnabled() {
        if (!enabled || timestampWindow == null || timestampWindow.isZero()
                || timestampWindow.isNegative() || timestampWindow.compareTo(Duration.ofHours(1)) > 0
                || secretsFile == null || secretsFile.isBlank()) {
            throw new IllegalStateException("Alert Webhook security configuration is incomplete");
        }
    }
}
