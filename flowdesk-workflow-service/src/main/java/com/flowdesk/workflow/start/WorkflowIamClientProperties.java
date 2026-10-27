package com.flowdesk.workflow.start;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 工作流调用 IAM 资格接口的可选直连地址、超时及服务身份凭证配置。
 * baseUrl 留空时按 IAM 注册服务名通过 Nacos 发现，显式地址仅覆盖这个客户端。
 * 资格校验每次重新读取租户令牌文件，以支持外部轮换，不缓存或记录令牌内容。
 */
@ConfigurationProperties(prefix = "flowdesk.workflow.iam")
public class WorkflowIamClientProperties {

    private String baseUrl = "";
    private String serviceTokenFile = "";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(3);

    /**
     * 读取IAM 服务地址，供组件装配及启动校验使用。
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 设置IAM 服务地址，供配置绑定和组件初始化使用。
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * 读取服务 JWT 文件路径，供组件装配及启动校验使用。
     */
    public String getServiceTokenFile() {
        return serviceTokenFile;
    }

    /**
     * 设置服务 JWT 文件路径，供配置绑定和组件初始化使用。
     */
    public void setServiceTokenFile(String serviceTokenFile) {
        this.serviceTokenFile = serviceTokenFile;
    }

    /**
     * 读取连接超时，供组件装配及启动校验使用。
     */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * 设置连接超时，供配置绑定和组件初始化使用。
     */
    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /**
     * 读取读取超时，供组件装配及启动校验使用。
     */
    public Duration getReadTimeout() {
        return readTimeout;
    }

    /**
     * 设置读取超时，供配置绑定和组件初始化使用。
     */
    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
