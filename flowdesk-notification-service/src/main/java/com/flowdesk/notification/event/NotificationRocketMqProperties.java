package com.flowdesk.notification.event;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Credentials and opt-in switch for the two Notification consumer directions. */
@ConfigurationProperties(prefix = "flowdesk.notification.consumer")
public class NotificationRocketMqProperties {
    public static final String EXPECTED_ACCESS_KEY = "flowdesknotification";

    private boolean enabled;
    private String nameServer = "127.0.0.1:9876";
    private String accessKey = "";
    private String secretKey = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getNameServer() { return nameServer; }
    public void setNameServer(String nameServer) { this.nameServer = nameServer; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public void validateEnabledConfiguration() {
        if (!enabled) throw new IllegalStateException("Notification RocketMQ consumers are disabled");
        if (nameServer == null || nameServer.isBlank()) {
            throw new IllegalStateException("Notification RocketMQ nameServer is required");
        }
        if (!EXPECTED_ACCESS_KEY.equals(accessKey)) {
            throw new IllegalStateException("Notification RocketMQ accessKey must be the frozen Notification identity");
        }
        if (secretKey == null || secretKey.isBlank() || secretKey.length() < 16) {
            throw new IllegalStateException("Notification RocketMQ secretKey must contain at least 16 characters");
        }
    }
}
