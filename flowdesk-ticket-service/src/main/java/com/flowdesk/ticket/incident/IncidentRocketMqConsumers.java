package com.flowdesk.ticket.incident;

import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.remoting.RPCHook;
import org.apache.rocketmq.remoting.protocol.heartbeat.MessageModel;
import org.springframework.context.SmartLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** 启动两组主消费者和两组独立死信接收器；先启动死信接收器，再接入主消息流。 */
public final class IncidentRocketMqConsumers implements SmartLifecycle{
    private static final Logger LOG=LoggerFactory.getLogger(IncidentRocketMqConsumers.class);
    private final IncidentConsumerProperties properties;private final IncidentMessageConsumer main;
    private final IncidentDlqAcceptor dlq;private final List<DefaultMQPushConsumer> consumers=new ArrayList<>();
    private final AtomicBoolean running=new AtomicBoolean();
    /**
     * 构造告警建单组件并保存调用依赖。
     */
    IncidentRocketMqConsumers(IncidentConsumerProperties p,IncidentMessageConsumer m,IncidentDlqAcceptor d){properties=p;main=m;dlq=d;}
    /**
     * 启动后台组件并注册消费或轮询任务，通过运行标记避免重复启动。
     */
    @Override public synchronized void start(){if(running.get())return;properties.validate();RPCHook acl=new AclClientRPCHook(
            new SessionCredentials(properties.getAccessKey(),properties.getSecretKey()));try{
        for(var b:bindings())consumers.add(create(b,true,acl,dlq::accept));for(var b:bindings())consumers.add(create(b,false,acl,main::consume));
        for(var c:consumers)c.start();running.set(true);LOG.info("Incident main consumers and DLQ acceptors are ready");}catch(Exception failure){for(var c:consumers)c.shutdown();consumers.clear();
        throw new IllegalStateException("Ticket Incident consumers failed closed",failure);}}
    /**
     * 按冻结契约创建告警建单消费者，绑定主消费或死信处理路径。
     */
    private DefaultMQPushConsumer create(IncidentMessageContract.Binding b,boolean acceptor,RPCHook acl,
            Function<IncidentTransportMessage,IncidentConsumeResult> handler)throws Exception{return createConsumer(b,acceptor,acl,
            properties.getNameServer(),properties.getRetryDelayLevel(),handler);}
    /**
     * 按冻结主题、消费组及重试预算创建 RocketMQ 消费者。
     */
    static DefaultMQPushConsumer createConsumer(IncidentMessageContract.Binding b,boolean acceptor,RPCHook acl,String nameserver,
            Function<IncidentTransportMessage,IncidentConsumeResult> handler)throws Exception{return createConsumer(b,acceptor,acl,nameserver,0,handler);}
    /**
     * 按冻结主题、消费组及重试预算创建 RocketMQ 消费者。
     */
    static DefaultMQPushConsumer createConsumer(IncidentMessageContract.Binding b,boolean acceptor,RPCHook acl,String nameserver,
            int retryDelayLevel,Function<IncidentTransportMessage,IncidentConsumeResult> handler)throws Exception{String group=acceptor?b.dlqAcceptorGroup():b.consumerGroup();
        String topic=acceptor?b.dlqTopic():b.topic();DefaultMQPushConsumer c=new DefaultMQPushConsumer(group,acl);c.setNamesrvAddr(nameserver.trim());
        c.setInstanceName("flowdesk-ticket-incident-"+b.kind().name().toLowerCase()+(acceptor?"-dlq-":"-main-")+UUID.randomUUID());
        c.setVipChannelEnabled(false);c.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);c.setMessageModel(MessageModel.CLUSTERING);
        c.setConsumeThreadMin(1);c.setConsumeThreadMax(1);c.setConsumeMessageBatchMaxSize(1);c.setPullBatchSize(1);
        c.setMaxReconsumeTimes(acceptor?IncidentMessageContract.DLQ_ACCEPTOR_MAX_RECONSUME_TIMES:IncidentMessageContract.MAX_RECONSUME_TIMES);
        c.subscribe(topic,"*");c.registerMessageListener((MessageListenerConcurrently)(messages,context)->handle(b,group,acceptor,retryDelayLevel,handler,messages,context));return c;}
    /**
     * 处理传输消息，完成契约验证、业务调用和确认结果映射。
     */
    static ConsumeConcurrentlyStatus handle(IncidentMessageContract.Binding b,String group,boolean acceptor,
            Function<IncidentTransportMessage,IncidentConsumeResult> handler,List<MessageExt> messages,ConsumeConcurrentlyContext context){return handle(b,group,acceptor,0,handler,messages,context);}
    /**
     * 处理传输消息，完成契约验证、业务调用和确认结果映射。
     */
    static ConsumeConcurrentlyStatus handle(IncidentMessageContract.Binding b,String group,boolean acceptor,int retryDelayLevel,
            Function<IncidentTransportMessage,IncidentConsumeResult> handler,List<MessageExt> messages,ConsumeConcurrentlyContext context){
        if(messages==null||messages.size()!=1)return retry(acceptor,retryDelayLevel,context);try{MessageExt m=messages.get(0);String topic=m.getTopic();
            if(acceptor&&context!=null&&context.getMessageQueue()!=null&&b.acceptorRetryTopic().equals(context.getMessageQueue().getTopic()))topic=b.dlqTopic();
            var result=handler.apply(new IncidentTransportMessage(topic,group,m.getKeys(),m.getTags(),m.getBody(),
                    m.getProperties()==null?Map.of():m.getProperties()));return result.disposition()==IncidentConsumeResult.Disposition.ACK?
                    ConsumeConcurrentlyStatus.CONSUME_SUCCESS:retry(acceptor,retryDelayLevel,context);}catch(RuntimeException failure){return retry(acceptor,retryDelayLevel,context);}}
    /**
     * 创建或执行受控重试安排，使尚未收敛的处理在后续投递中继续。
     */
    private static ConsumeConcurrentlyStatus retry(boolean acceptor,int retryDelayLevel,ConsumeConcurrentlyContext c){if(c!=null){if(acceptor)c.setDelayLevelWhenNextConsume(
            IncidentMessageContract.DLQ_ACCEPTOR_RETRY_DELAY_LEVEL);else if(retryDelayLevel>0)c.setDelayLevelWhenNextConsume(retryDelayLevel);}return ConsumeConcurrentlyStatus.RECONSUME_LATER;}
    /**
     * 返回当前消费者的绑定集合，保持主主题与死信路径契约一致。
     */
    private static IncidentMessageContract.Binding[] bindings(){return new IncidentMessageContract.Binding[]{IncidentMessageContract.CREATE_REQUESTED,IncidentMessageContract.RECOVERED};}
    /**
     * 停止后台组件并取消任务或释放资源，存在回调时通知停止完成。
     */
    @Override public synchronized void stop(){running.set(false);for(var c:consumers)c.shutdown();consumers.clear();}
    /**
     * 返回运行标记，供 Spring 生命周期和调度分支判断。
     */
    @Override public boolean isRunning(){return running.get();}
    /**
     * 返回生命周期阶段，决定关联组件启动和停止顺序。
     */
    @Override public int getPhase(){return Integer.MIN_VALUE+100;}
}
