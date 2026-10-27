package com.flowdesk.alert.event;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定告警 Outbox 发布器的启用开关、NameServer 和 ACL 凭据。
 *
 * <p>消息主题与生产组由固定契约决定，不通过这些配置改写。启动前检查连接地址、预期的 Alert 身份
 * 和凭据是否完整，配置不满足要求时直接阻止发布器启动，避免以错误身份反复投递。
 */
@ConfigurationProperties(prefix = "flowdesk.alert.outbox.publisher")
class AlertOutboxProperties {
    private boolean enabled;
    private String nameServer = "127.0.0.1:9876", accessKey = "", secretKey = "";

    /**
     * 返回组件启用开关，供组件组装或运行时判断使用。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 更新组件启用开关，供 Spring 配置绑定及组件初始化使用。
     */
    public void setEnabled(boolean v) {
        enabled = v;
    }

    /**
     * 返回RocketMQ NameServer 地址，供组件组装或运行时判断使用。
     */
    public String getNameServer() {
        return nameServer;
    }

    /**
     * 更新RocketMQ NameServer 地址，供 Spring 配置绑定及组件初始化使用。
     */
    public void setNameServer(String v) {
        nameServer = v;
    }

    /**
     * 返回RocketMQ ACL 访问标识，供组件组装或运行时判断使用。
     */
    public String getAccessKey() {
        return accessKey;
    }

    /**
     * 更新RocketMQ ACL 访问标识，供 Spring 配置绑定及组件初始化使用。
     */
    public void setAccessKey(String v) {
        accessKey = v;
    }

    /**
     * 返回RocketMQ ACL 签名密钥，供组件组装或运行时判断使用。
     */
    public String getSecretKey() {
        return secretKey;
    }

    /**
     * 更新RocketMQ ACL 签名密钥，供 Spring 配置绑定及组件初始化使用。
     */
    public void setSecretKey(String v) {
        secretKey = v;
    }

    /** 仅校验启动所需配置；连接是否成功由 SDK 启动阶段判断，凭据内容不应写入日志。 */
    void validate() {
        if (!enabled
                || nameServer == null
                || nameServer.isBlank()
                || !"flowdeskalert".equals(accessKey)
                || secretKey == null
                || secretKey.length() < 16)
            throw new IllegalStateException("Alert M7 Outbox configuration is invalid");
    }
}
