package com.flowdesk.alert.event;

import java.util.Map;

public record TicketResultTransportMessage(String topic,String consumerGroup,String keys,String tags,byte[] body,Map<String,String> properties){
    public TicketResultTransportMessage{body=body==null?null:body.clone();properties=properties==null?Map.of():Map.copyOf(properties);}
    @Override public byte[] body(){return body==null?null:body.clone();}}
