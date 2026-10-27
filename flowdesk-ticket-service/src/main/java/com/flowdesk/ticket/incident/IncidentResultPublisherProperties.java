package com.flowdesk.ticket.incident;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Incident 建单结果发布端的启用开关、RocketMQ 连接凭据和轮询配置。 */
@ConfigurationProperties(prefix="flowdesk.ticket.incident-result-publisher")
public class IncidentResultPublisherProperties{private boolean enabled;private String nameServer="127.0.0.1:9876",accessKey="",secretKey="";
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
     * 校验告警建单启用所需配置、数值范围及时间关系，无效配置拒绝启动。
     */
    void validate(){if(!enabled||nameServer==null||nameServer.isBlank()
            ||!"flowdeskticket".equals(accessKey)||secretKey==null||secretKey.length()<16)throw new IllegalStateException("Ticket Incident result publisher configuration is invalid");}}
