package com.flowdesk.alert.event;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定工单关联结果主消费者和死信接纳器共用的 Broker 连接参数。
 *
 * <p>启用开关决定是否启动实时订阅，主题与消费组仍取固定消息契约。启动前要求配置预期的 Alert ACL 身份、
 * 非空地址与足够长度的凭据；配置检查通过并不替代 Broker 端的认证和授权。
 */
@ConfigurationProperties(prefix = "flowdesk.alert.ticket-result.consumer")
class TicketResultProperties {
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

    /** 在创建或启动消费者前拒绝不完整配置，避免启动成只有部分消息方向可工作的状态。 */
    void validate() {
        if (!enabled
                || nameServer == null
                || nameServer.isBlank()
                || !"flowdeskalert".equals(accessKey)
                || secretKey == null
                || secretKey.length() < 16)
            throw new IllegalStateException(
                    "Alert Ticket result consumer configuration is invalid");
    }
}
