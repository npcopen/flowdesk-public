package com.flowdesk.workflow.start;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Workflow -> IAM API-016 client configuration. */
@ConfigurationProperties(prefix = "flowdesk.workflow.iam")
public class WorkflowIamClientProperties {

    private String baseUrl = "http://127.0.0.1:8081";
    private String serviceTokenFile = "";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(3);

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getServiceTokenFile() {
        return serviceTokenFile;
    }

    public void setServiceTokenFile(String serviceTokenFile) {
        this.serviceTokenFile = serviceTokenFile;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
