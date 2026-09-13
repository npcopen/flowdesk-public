package com.flowdesk.ticket.incident;

import java.util.Map;

public record IncidentTransportMessage(String topic,String consumerGroup,String keys,String tags,
                                       byte[] body,Map<String,String> userProperties){
    public IncidentTransportMessage{body=body==null?null:body.clone();
        userProperties=userProperties==null?Map.of():Map.copyOf(userProperties);}
    @Override public byte[] body(){return body==null?null:body.clone();}
}
