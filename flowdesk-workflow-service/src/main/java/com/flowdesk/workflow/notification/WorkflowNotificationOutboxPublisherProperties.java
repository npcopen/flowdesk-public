package com.flowdesk.workflow.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flowdesk.workflow.notification.outbox.publisher")
public class WorkflowNotificationOutboxPublisherProperties {
    public static final String EXPECTED_ACCESS_KEY = "flowdeskworkflow";
    private boolean enabled;
    private String nameServer = "127.0.0.1:9876";
    private String accessKey = "";
    private String secretKey = "";
    private long pollIntervalMillis = 500;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getNameServer() { return nameServer; }
    public void setNameServer(String nameServer) { this.nameServer = nameServer; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public long getPollIntervalMillis() { return pollIntervalMillis; }
    public void setPollIntervalMillis(long pollIntervalMillis) { this.pollIntervalMillis = pollIntervalMillis; }

    public void validateEnabledConfiguration() {
        if (!enabled) throw new IllegalStateException("Workflow Notification Outbox is disabled");
        if (nameServer == null || nameServer.isBlank()) throw new IllegalStateException("Workflow Notification nameServer is required");
        if (!EXPECTED_ACCESS_KEY.equals(accessKey)) throw new IllegalStateException("Workflow Notification accessKey must be the frozen Workflow identity");
        if (secretKey == null || secretKey.isBlank() || secretKey.length() < 16) throw new IllegalStateException("Workflow Notification secretKey must contain at least 16 characters");
        if (pollIntervalMillis < 100 || pollIntervalMillis > 60_000) throw new IllegalStateException("Workflow Notification poll interval is invalid");
    }
}
