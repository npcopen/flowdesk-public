package com.flowdesk.workflow.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 反向事件 Outbox 的发布开关、扫描批量、轮询和失败重试配置。
 */
@ConfigurationProperties(prefix = "flowdesk.workflow.outbox.publisher")
public class WorkflowOutboxPublisherProperties {
    static final String EXPECTED_ACCESS_KEY = "flowdeskworkflow";
    private boolean enabled;
    private String nameServer = "127.0.0.1:9876";
    private String accessKey = "";
    private String secretKey = "";

    /**
     * 读取功能启用开关，供组件装配及启动校验使用。
     */
    public boolean isEnabled() { return enabled; }
    /**
     * 设置功能启用开关，供配置绑定和组件初始化使用。
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    /**
     * 读取RocketMQ NameServer 地址，供组件装配及启动校验使用。
     */
    public String getNameServer() { return nameServer; }
    /**
     * 设置RocketMQ NameServer 地址，供配置绑定和组件初始化使用。
     */
    public void setNameServer(String nameServer) { this.nameServer = nameServer; }
    /**
     * 读取RocketMQ 访问身份，供组件装配及启动校验使用。
     */
    public String getAccessKey() { return accessKey; }
    /**
     * 设置RocketMQ 访问身份，供配置绑定和组件初始化使用。
     */
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    /**
     * 读取RocketMQ 密钥，供组件装配及启动校验使用。
     */
    public String getSecretKey() { return secretKey; }
    /**
     * 设置RocketMQ 密钥，供配置绑定和组件初始化使用。
     */
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    /**
     * 校验发布器启用配置和固定身份，防止以错误账号启动消息生产者。
     */
    void validateEnabled() {
        if (!enabled || nameServer == null || nameServer.isBlank()
                || !EXPECTED_ACCESS_KEY.equals(accessKey)
                || secretKey == null || secretKey.length() < 16) {
            throw new IllegalStateException("Workflow Outbox RocketMQ configuration is invalid");
        }
    }
}
