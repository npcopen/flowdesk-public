package com.flowdesk.workflow.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审批通知 Outbox 发布开关、轮询间隔、批量大小和重试策略配置。
 */
@ConfigurationProperties(prefix = "flowdesk.workflow.notification.outbox.publisher")
public class WorkflowNotificationOutboxPublisherProperties {
    public static final String EXPECTED_ACCESS_KEY = "flowdeskworkflow";
    private boolean enabled;
    private String nameServer = "127.0.0.1:9876";
    private String accessKey = "";
    private String secretKey = "";
    private long pollIntervalMillis = 500;

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
     * 读取轮询间隔，供组件装配及启动校验使用。
     */
    public long getPollIntervalMillis() { return pollIntervalMillis; }
    /**
     * 设置轮询间隔，供配置绑定和组件初始化使用。
     */
    public void setPollIntervalMillis(long pollIntervalMillis) { this.pollIntervalMillis = pollIntervalMillis; }

    /**
     * 组件启用时严格验证消息代理地址及固定访问身份，不合法配置阻止启动。
     */
    public void validateEnabledConfiguration() {
        if (!enabled) throw new IllegalStateException("Workflow Notification Outbox is disabled");
        if (nameServer == null || nameServer.isBlank()) throw new IllegalStateException("Workflow Notification nameServer is required");
        if (!EXPECTED_ACCESS_KEY.equals(accessKey)) throw new IllegalStateException("Workflow Notification accessKey must be the frozen Workflow identity");
        if (secretKey == null || secretKey.isBlank() || secretKey.length() < 16) throw new IllegalStateException("Workflow Notification secretKey must contain at least 16 characters");
        if (pollIntervalMillis < 100 || pollIntervalMillis > 60_000) throw new IllegalStateException("Workflow Notification poll interval is invalid");
    }
}
