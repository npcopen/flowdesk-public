package com.flowdesk.ticket.iam;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工单服务调用 IAM 的可选直连地址、超时与服务令牌文件配置。
 * baseUrl 留空时通过 Nacos 服务发现寻址，填写 HTTP(S) 地址时仅覆盖这个 Feign 客户端。
 * 令牌由 IAM 引导任务签发，每次资格校验均重新读取文件以支持外部轮换，不缓存或记录令牌内容。
 */
@ConfigurationProperties(prefix = "flowdesk.ticket.iam")
public class TicketIamClientProperties {

    private String baseUrl = "";
    private String serviceTokenFile = "";
    private java.time.Duration connectTimeout = java.time.Duration.ofSeconds(2);
    private java.time.Duration readTimeout = java.time.Duration.ofSeconds(3);

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
     * 返回服务令牌文件路径，供组件按配置执行。
     */
    public String getServiceTokenFile() {
        return serviceTokenFile;
    }

    /**
     * 设置服务令牌文件路径，供配置绑定与启动校验使用。
     */
    public void setServiceTokenFile(String serviceTokenFile) {
        this.serviceTokenFile = serviceTokenFile;
    }

    /**
     * 返回连接超时，供组件按配置执行。
     */
    public java.time.Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * 设置连接超时，供配置绑定与启动校验使用。
     */
    public void setConnectTimeout(java.time.Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /**
     * 返回读取超时，供组件按配置执行。
     */
    public java.time.Duration getReadTimeout() {
        return readTimeout;
    }

    /**
     * 设置读取超时，供配置绑定与启动校验使用。
     */
    public void setReadTimeout(java.time.Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
