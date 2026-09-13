package com.flowdesk.workflow.start;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Credentials and opt-in switch for both frozen Workflow consumer groups. */
@ConfigurationProperties(prefix = "flowdesk.workflow.start.consumer")
public class WorkflowStartRocketMqProperties {

    static final String EXPECTED_ACCESS_KEY = "flowdeskworkflow";

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
            throw new IllegalStateException("Workflow RocketMQ consumers are disabled");
        }
        if (nameServer == null || nameServer.isBlank()) {
            throw new IllegalStateException("Workflow RocketMQ nameServer is required");
        }
        if (!EXPECTED_ACCESS_KEY.equals(accessKey)) {
            throw new IllegalStateException(
                    "Workflow RocketMQ accessKey must be the frozen Workflow identity");
        }
        if (secretKey == null || secretKey.isBlank() || secretKey.length() < 16) {
            throw new IllegalStateException(
                    "Workflow RocketMQ secretKey must contain at least 16 characters");
        }
    }
}
