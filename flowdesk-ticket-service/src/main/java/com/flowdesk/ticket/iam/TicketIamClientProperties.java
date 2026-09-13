package com.flowdesk.ticket.iam;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ticket -> IAM internal eligibility client configuration.
 *
 * <p>{@code service-token-file} points at a file minted by the IAM bootstrap runner
 * ({@code flowdesk.iam.service-jwt-bootstrap}). The token is re-read for every
 * eligibility call, so an external rotation process can refresh the file; Ticket never
 * logs or caches token material.</p>
 */
@ConfigurationProperties(prefix = "flowdesk.ticket.iam")
public class TicketIamClientProperties {

    private String baseUrl = "http://127.0.0.1:8081";
    private String serviceTokenFile = "";
    private java.time.Duration connectTimeout = java.time.Duration.ofSeconds(2);
    private java.time.Duration readTimeout = java.time.Duration.ofSeconds(3);

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

    public java.time.Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(java.time.Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public java.time.Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(java.time.Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
