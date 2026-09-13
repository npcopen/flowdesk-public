package com.flowdesk.ticket.incident;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="flowdesk.ticket.incident-result-publisher")
public class IncidentResultPublisherProperties{private boolean enabled;private String nameServer="127.0.0.1:9876",accessKey="",secretKey="";
    public boolean isEnabled(){return enabled;}public void setEnabled(boolean v){enabled=v;}public String getNameServer(){return nameServer;}
    public void setNameServer(String v){nameServer=v;}public String getAccessKey(){return accessKey;}public void setAccessKey(String v){accessKey=v;}
    public String getSecretKey(){return secretKey;}public void setSecretKey(String v){secretKey=v;}void validate(){if(!enabled||nameServer==null||nameServer.isBlank()
            ||!"flowdeskticket".equals(accessKey)||secretKey==null||secretKey.length()<16)throw new IllegalStateException("Ticket Incident result publisher configuration is invalid");}}
