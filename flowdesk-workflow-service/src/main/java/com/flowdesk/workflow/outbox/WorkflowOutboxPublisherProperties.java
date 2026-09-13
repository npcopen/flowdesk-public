package com.flowdesk.workflow.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flowdesk.workflow.outbox.publisher")
public class WorkflowOutboxPublisherProperties {
    static final String EXPECTED_ACCESS_KEY = "flowdeskworkflow";
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

    void validateEnabled() {
        if (!enabled || nameServer == null || nameServer.isBlank()
                || !EXPECTED_ACCESS_KEY.equals(accessKey)
                || secretKey == null || secretKey.length() < 16) {
            throw new IllegalStateException("Workflow Outbox RocketMQ configuration is invalid");
        }
    }
}
