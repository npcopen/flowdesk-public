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

/** 工单通知绑定的同步 RocketMQ 发送适配器，将 Broker 结果转换为统一发送结果。 */
public final class RocketMqTicketNotificationOutboxTransport implements TicketNotificationOutboxTransport, AutoCloseable {
    static final String NOT_CONFIRMED="BROKER_SEND_NOT_CONFIRMED";
    private final DefaultMQProducer producer; private final AtomicBoolean started=new AtomicBoolean();
    /**
     * 构造工单通知组件并保存调用依赖。
     */
    RocketMqTicketNotificationOutboxTransport(DefaultMQProducer producer){this.producer=Objects.requireNonNull(producer);}
    /**
     * 校验工单通知创建请求并保存事实，按接口约定处理幂等及版本条件。
     */
    public static RocketMqTicketNotificationOutboxTransport create(TicketNotificationOutboxPublisherProperties p){
        p.validateEnabledConfiguration(); RPCHook hook=new AclClientRPCHook(new SessionCredentials(p.getAccessKey(),p.getSecretKey()));
        DefaultMQProducer producer=new DefaultMQProducer(NotificationEventContract.TICKET.producerGroup(),hook);
        producer.setNamesrvAddr(p.getNameServer().trim()); producer.setInstanceName("flowdesk-ticket-notification-outbox-"+UUID.randomUUID());
        producer.setVipChannelEnabled(false); producer.setSendMsgTimeout(Math.toIntExact(NotificationEventContract.SEND_TIMEOUT.toMillis()));
        producer.setRetryTimesWhenSendFailed(NotificationEventContract.SDK_SEND_RETRIES); producer.setRetryTimesWhenSendAsyncFailed(NotificationEventContract.SDK_SEND_RETRIES); producer.setRetryAnotherBrokerWhenNotStoreOK(false);
        return new RocketMqTicketNotificationOutboxTransport(producer);
    }
    /**
     * 启动后台组件并注册消费或轮询任务，通过运行标记避免重复启动。
     */
    public synchronized void start() throws MQClientException { if(started.get())return; try{producer.start();started.set(true);}catch(MQClientException|RuntimeException e){producer.shutdown();throw e;} }
    /**
     * 发送已保存的发布内容，按传输确认结果推进或保留发件箱状态。
     */
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
    /**
     * 关闭组件持有的资源并终止后续处理。
     */
    @Override public synchronized void close(){if(started.compareAndSet(true,false))producer.shutdown();}
    /**
     * 提供生产者测试入口，供验证实际传输参数及发送结果映射。
     */
    DefaultMQProducer producerForTest(){return producer;}
}
