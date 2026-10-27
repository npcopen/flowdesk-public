package com.flowdesk.ticket.reverse;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 流程反向事件消费者的启用开关、RocketMQ 地址、凭据及重试配置。 */
@ConfigurationProperties(prefix = "flowdesk.ticket.workflow-reverse.consumer")
public class WorkflowReverseRocketMqProperties {
    private boolean enabled;
    private String nameServer = "127.0.0.1:9876";
    private String accessKey = "";
    private String secretKey = "";
    /**
     * 返回功能开关，供组件按配置执行。
     */
    public boolean isEnabled() { return enabled; }
    /**
     * 设置功能开关，供配置绑定与启动校验使用。
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    /**
     * 返回RocketMQ NameServer 地址，供组件按配置执行。
     */
    public String getNameServer() { return nameServer; }
    /**
     * 设置RocketMQ NameServer 地址，供配置绑定与启动校验使用。
     */
    public void setNameServer(String nameServer) { this.nameServer = nameServer; }
    /**
     * 返回访问身份，供组件按配置执行。
     */
    public String getAccessKey() { return accessKey; }
    /**
     * 设置访问身份，供配置绑定与启动校验使用。
     */
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    /**
     * 返回认证密钥，供组件按配置执行。
     */
    public String getSecretKey() { return secretKey; }
    /**
     * 设置认证密钥，供配置绑定与启动校验使用。
     */
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    /**
     * 显式启用工作流反向结果时校验必需配置，防止缺失认证或连接参数仍启动。
     */
    void validateEnabled() {
        if (!enabled || nameServer == null || nameServer.isBlank()
                || !"flowdeskticket".equals(accessKey)
                || secretKey == null || secretKey.length() < 16) {
            throw new IllegalStateException("Ticket reverse-consumer RocketMQ configuration is invalid");
        }
    }
}
