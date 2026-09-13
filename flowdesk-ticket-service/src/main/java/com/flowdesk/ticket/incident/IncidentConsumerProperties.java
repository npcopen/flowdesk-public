package com.flowdesk.ticket.incident;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="flowdesk.ticket.incident-consumer")
public class IncidentConsumerProperties{private boolean enabled;private String nameServer="127.0.0.1:9876";
    private String accessKey="",secretKey="";private int retryDelayLevel;
    public boolean isEnabled(){return enabled;}public void setEnabled(boolean v){enabled=v;}
    public String getNameServer(){return nameServer;}public void setNameServer(String v){nameServer=v;}
    public String getAccessKey(){return accessKey;}public void setAccessKey(String v){accessKey=v;}
    public String getSecretKey(){return secretKey;}public void setSecretKey(String v){secretKey=v;}
    public int getRetryDelayLevel(){return retryDelayLevel;}public void setRetryDelayLevel(int v){retryDelayLevel=v;}
    void validate(){if(!enabled||nameServer==null||nameServer.isBlank()||!"flowdeskticket".equals(accessKey)
            ||secretKey==null||secretKey.length()<16||retryDelayLevel<0||retryDelayLevel>18)
        throw new IllegalStateException("Ticket Incident RocketMQ configuration is invalid");}}
