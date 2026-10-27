package com.flowdesk.ticket.workflow;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 工单服务调用 Workflow 的可选直连地址和超时；地址留空时按服务名发现。 */
@ConfigurationProperties(prefix = "flowdesk.ticket.workflow")
public class TicketWorkflowClientProperties {

    private String baseUrl = "";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(3);

    /**
     * 返回下游服务地址，供组件按配置执行。
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 设置下游服务地址，供配置绑定与启动校验使用。
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * 返回连接超时，供组件按配置执行。
     */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * 设置连接超时，供配置绑定与启动校验使用。
     */
    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /**
     * 返回读取超时，供组件按配置执行。
     */
    public Duration getReadTimeout() {
        return readTimeout;
    }

    /**
     * 设置读取超时，供配置绑定与启动校验使用。
     */
    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
