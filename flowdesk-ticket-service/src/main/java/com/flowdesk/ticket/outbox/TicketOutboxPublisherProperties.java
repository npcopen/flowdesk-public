package com.flowdesk.ticket.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-only switches and credentials for the frozen Ticket Outbox publisher. */
@ConfigurationProperties(prefix = "flowdesk.ticket.outbox.publisher")
public class TicketOutboxPublisherProperties {

    static final String EXPECTED_ACCESS_KEY = "flowdeskticket";

    private boolean enabled;
    private String nameServer = "127.0.0.1:9876";
    private String accessKey = "";
    private String secretKey = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNameServer() {
        return nameServer;
    }

    public void setNameServer(String nameServer) {
        this.nameServer = nameServer;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    void validateEnabledConfiguration() {
        if (!enabled) {
            throw new IllegalStateException("Ticket Outbox publisher configuration is disabled");
        }
        if (nameServer == null || nameServer.isBlank()) {
            throw new IllegalStateException("Ticket Outbox RocketMQ nameServer is required");
        }
        if (!EXPECTED_ACCESS_KEY.equals(accessKey)) {
            throw new IllegalStateException(
                    "Ticket Outbox RocketMQ accessKey must be the frozen Ticket identity");
        }
        if (secretKey == null || secretKey.isBlank() || secretKey.length() < 16) {
            throw new IllegalStateException(
                    "Ticket Outbox RocketMQ secretKey must contain at least 16 characters");
        }
    }
}
