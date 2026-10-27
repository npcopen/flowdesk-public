package com.flowdesk.ticket.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 固定工单 Outbox 发布器的部署开关和连接凭据，仅由部署配置控制。 */
@ConfigurationProperties(prefix = "flowdesk.ticket.outbox.publisher")
public class TicketOutboxPublisherProperties {

    static final String EXPECTED_ACCESS_KEY = "flowdeskticket";

    private boolean enabled;
    private String nameServer = "127.0.0.1:9876";
    private String accessKey = "";
    private String secretKey = "";

    /**
     * 返回功能开关，供组件按配置执行。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置功能开关，供配置绑定与启动校验使用。
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 返回RocketMQ NameServer 地址，供组件按配置执行。
     */
    public String getNameServer() {
        return nameServer;
    }

    /**
     * 设置RocketMQ NameServer 地址，供配置绑定与启动校验使用。
     */
    public void setNameServer(String nameServer) {
        this.nameServer = nameServer;
    }

    /**
     * 返回访问身份，供组件按配置执行。
     */
    public String getAccessKey() {
        return accessKey;
    }

    /**
     * 设置访问身份，供配置绑定与启动校验使用。
     */
    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    /**
     * 返回认证密钥，供组件按配置执行。
     */
    public String getSecretKey() {
        return secretKey;
    }

    /**
     * 设置认证密钥，供配置绑定与启动校验使用。
     */
    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    /**
     * 显式启用工作流启动发布时校验必需配置，防止缺失认证或连接参数仍启动。
     */
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
