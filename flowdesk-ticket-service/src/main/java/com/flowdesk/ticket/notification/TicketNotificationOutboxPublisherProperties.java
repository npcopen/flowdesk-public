package com.flowdesk.ticket.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flowdesk.ticket.notification.outbox.publisher")
public class TicketNotificationOutboxPublisherProperties {
    public static final String EXPECTED_ACCESS_KEY = "flowdeskticket";
    private boolean enabled;
    private String nameServer = "127.0.0.1:9876";
    private String accessKey = "";
    private String secretKey = "";
    private long pollIntervalMillis = 500;
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public String getNameServer(){return nameServer;} public void setNameServer(String v){nameServer=v;}
    public String getAccessKey(){return accessKey;} public void setAccessKey(String v){accessKey=v;}
    public String getSecretKey(){return secretKey;} public void setSecretKey(String v){secretKey=v;}
    public long getPollIntervalMillis(){return pollIntervalMillis;} public void setPollIntervalMillis(long v){pollIntervalMillis=v;}
    public void validateEnabledConfiguration(){
        if(!enabled) throw new IllegalStateException("Ticket Notification Outbox is disabled");
        if(nameServer==null||nameServer.isBlank()) throw new IllegalStateException("Ticket Notification nameServer is required");
        if(!EXPECTED_ACCESS_KEY.equals(accessKey)) throw new IllegalStateException("Ticket Notification accessKey must be the frozen Ticket identity");
        if(secretKey==null||secretKey.isBlank()||secretKey.length()<16) throw new IllegalStateException("Ticket Notification secretKey must contain at least 16 characters");
        if(pollIntervalMillis<100||pollIntervalMillis>60000) throw new IllegalStateException("Ticket Notification poll interval is invalid");
    }
}
