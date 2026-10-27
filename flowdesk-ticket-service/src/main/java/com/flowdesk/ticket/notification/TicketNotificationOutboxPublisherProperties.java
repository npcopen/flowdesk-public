package com.flowdesk.ticket.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 工单通知发布的启用开关、RocketMQ 凭据和轮询间隔配置。 */
@ConfigurationProperties(prefix = "flowdesk.ticket.notification.outbox.publisher")
public class TicketNotificationOutboxPublisherProperties {
    public static final String EXPECTED_ACCESS_KEY = "flowdeskticket";
    private boolean enabled;
    private String nameServer = "127.0.0.1:9876";
    private String accessKey = "";
    private String secretKey = "";
    private long pollIntervalMillis = 500;
    /**
     * 返回功能开关，供组件按配置执行。
     */
    public boolean isEnabled(){return enabled;}
    /**
     * 设置功能开关，供配置绑定与启动校验使用。
     */
    public void setEnabled(boolean v){enabled=v;}
    /**
     * 返回RocketMQ NameServer 地址，供组件按配置执行。
     */
    public String getNameServer(){return nameServer;}
    /**
     * 设置RocketMQ NameServer 地址，供配置绑定与启动校验使用。
     */
    public void setNameServer(String v){nameServer=v;}
    /**
     * 返回访问身份，供组件按配置执行。
     */
    public String getAccessKey(){return accessKey;}
    /**
     * 设置访问身份，供配置绑定与启动校验使用。
     */
    public void setAccessKey(String v){accessKey=v;}
    /**
     * 返回认证密钥，供组件按配置执行。
     */
    public String getSecretKey(){return secretKey;}
    /**
     * 设置认证密钥，供配置绑定与启动校验使用。
     */
    public void setSecretKey(String v){secretKey=v;}
    /**
     * 返回轮询间隔毫秒数，供组件按配置执行。
     */
    public long getPollIntervalMillis(){return pollIntervalMillis;}
    /**
     * 设置轮询间隔毫秒数，供配置绑定与启动校验使用。
     */
    public void setPollIntervalMillis(long v){pollIntervalMillis=v;}
    /**
     * 显式启用工单通知时校验必需配置，防止缺失认证或连接参数仍启动。
     */
    public void validateEnabledConfiguration(){
        if(!enabled) throw new IllegalStateException("Ticket Notification Outbox is disabled");
        if(nameServer==null||nameServer.isBlank()) throw new IllegalStateException("Ticket Notification nameServer is required");
        if(!EXPECTED_ACCESS_KEY.equals(accessKey)) throw new IllegalStateException("Ticket Notification accessKey must be the frozen Ticket identity");
        if(secretKey==null||secretKey.isBlank()||secretKey.length()<16) throw new IllegalStateException("Ticket Notification secretKey must contain at least 16 characters");
        if(pollIntervalMillis<100||pollIntervalMillis>60000) throw new IllegalStateException("Ticket Notification poll interval is invalid");
    }
}
