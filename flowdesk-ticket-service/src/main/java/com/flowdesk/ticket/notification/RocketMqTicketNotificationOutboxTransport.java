package com.flowdesk.ticket.notification;

import com.flowdesk.common.notification.NotificationEventContract;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.RPCHook;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Synchronous RocketMQ adapter for the Ticket Notification binding. */
public final class RocketMqTicketNotificationOutboxTransport implements TicketNotificationOutboxTransport, AutoCloseable {
    static final String NOT_CONFIRMED="BROKER_SEND_NOT_CONFIRMED";
    private final DefaultMQProducer producer; private final AtomicBoolean started=new AtomicBoolean();
    RocketMqTicketNotificationOutboxTransport(DefaultMQProducer producer){this.producer=Objects.requireNonNull(producer);}
    public static RocketMqTicketNotificationOutboxTransport create(TicketNotificationOutboxPublisherProperties p){
        p.validateEnabledConfiguration(); RPCHook hook=new AclClientRPCHook(new SessionCredentials(p.getAccessKey(),p.getSecretKey()));
        DefaultMQProducer producer=new DefaultMQProducer(NotificationEventContract.TICKET.producerGroup(),hook);
        producer.setNamesrvAddr(p.getNameServer().trim()); producer.setInstanceName("flowdesk-ticket-notification-outbox-"+UUID.randomUUID());
        producer.setVipChannelEnabled(false); producer.setSendMsgTimeout(Math.toIntExact(NotificationEventContract.SEND_TIMEOUT.toMillis()));
        producer.setRetryTimesWhenSendFailed(NotificationEventContract.SDK_SEND_RETRIES); producer.setRetryTimesWhenSendAsyncFailed(NotificationEventContract.SDK_SEND_RETRIES); producer.setRetryAnotherBrokerWhenNotStoreOK(false);
        return new RocketMqTicketNotificationOutboxTransport(producer);
    }
    public synchronized void start() throws MQClientException { if(started.get())return; try{producer.start();started.set(true);}catch(MQClientException|RuntimeException e){producer.shutdown();throw e;} }
    @Override public TicketNotificationOutboxSendResult send(TicketNotificationOutboxMessage value)throws Exception{
        if(!started.get())throw new IllegalStateException("Ticket Notification producer is not started");
        if(!NotificationEventContract.TICKET.topic().equals(value.topic()))throw new IllegalArgumentException("Ticket Notification topic is not frozen");
        Message message=new Message(value.topic(),value.body()); message.setKeys(value.keys()); message.setWaitStoreMsgOK(true);
        for(Map.Entry<String,String> e:value.userProperties().entrySet())message.putUserProperty(e.getKey(),e.getValue());
        SendResult result=producer.send(message,NotificationEventContract.SEND_TIMEOUT.toMillis());
        if(result!=null&&result.getSendStatus()==SendStatus.SEND_OK)return TicketNotificationOutboxSendResult.sendOk();
        String summary=result==null||result.getSendStatus()==null?null:"RocketMQ send status "+result.getSendStatus().name();
        return TicketNotificationOutboxSendResult.notConfirmed(NOT_CONFIRMED,summary);
    }
    @Override public synchronized void close(){if(started.compareAndSet(true,false))producer.shutdown();}
    DefaultMQProducer producerForTest(){return producer;}
}
