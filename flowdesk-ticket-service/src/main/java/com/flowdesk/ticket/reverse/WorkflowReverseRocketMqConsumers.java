package com.flowdesk.ticket.reverse;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** 为两类流程反向事件分别启动主消费组和死信接收组，共四个相互独立的消费组。 */
public final class WorkflowReverseRocketMqConsumers implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(
            WorkflowReverseRocketMqConsumers.class);
    private final WorkflowReverseRocketMqProperties properties;
    private final Function<WorkflowReverseTransportMessage, WorkflowReverseConsumeResult> main;
    private final Function<WorkflowReverseTransportMessage, WorkflowReverseConsumeResult> dlq;
    private final AtomicBoolean running = new AtomicBoolean();
    private final List<DefaultMQPushConsumer> consumers = new ArrayList<>();

    /**
     * 构造工作流反向结果组件并保存调用依赖。
     */
    WorkflowReverseRocketMqConsumers(WorkflowReverseRocketMqProperties properties,
                                     WorkflowReverseConsumer main,
                                     WorkflowReverseDlqAcceptor dlq) {
        this.properties = properties; this.main = main::consume; this.dlq = dlq::accept;
    }

    /**
     * 启动后台组件并注册消费或轮询任务，通过运行标记避免重复启动。
     */
    @Override public synchronized void start() {
        if (running.get()) return;
        properties.validateEnabled();
        RPCHook acl = new AclClientRPCHook(new SessionCredentials(
                properties.getAccessKey(), properties.getSecretKey()));
        try {
            for (var binding : bindings()) {
                consumers.add(create(binding.dlqAcceptorGroup(), binding.dlqTopic(),
                        binding, true, acl, dlq));
            }
            for (var binding : bindings()) {
                consumers.add(create(binding.consumerGroup(), binding.topic(),
                        binding, false, acl, main));
            }
            for (DefaultMQPushConsumer consumer : consumers) consumer.start();
            running.set(true);
            LOG.info("Workflow reverse main consumers and DLQ acceptors are ready");
        }
        catch (Exception failure) {
            for (DefaultMQPushConsumer consumer : consumers) consumer.shutdown();
            consumers.clear();
            throw new IllegalStateException("Ticket reverse consumers failed closed", failure);
        }
    }

    /**
     * 按冻结契约创建工作流反向结果消费者，绑定主消费或死信处理路径。
     */
    private DefaultMQPushConsumer create(String group, String topic,
                                         WorkflowReverseContract.Binding binding,
                                         boolean acceptor, RPCHook acl,
                                         Function<WorkflowReverseTransportMessage,
                                                 WorkflowReverseConsumeResult> handler)
            throws Exception {
        return createConsumer(group, topic, binding, acceptor, acl,
                properties.getNameServer(), handler);
    }

    /**
     * 按冻结主题、消费组及重试预算创建 RocketMQ 消费者。
     */
    static DefaultMQPushConsumer createConsumer(
            String group, String topic, WorkflowReverseContract.Binding binding,
            boolean acceptor, RPCHook acl, String nameServer,
            Function<WorkflowReverseTransportMessage, WorkflowReverseConsumeResult> handler)
            throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(group, acl);
        consumer.setNamesrvAddr(nameServer.trim());
        consumer.setInstanceName("flowdesk-ticket-reverse-" + binding.kind().name().toLowerCase()
                + (acceptor ? "-dlq-" : "-main-") + UUID.randomUUID());
        consumer.setVipChannelEnabled(false);
        // 新建 M6 消费组需补消费工单服务启动期间已发布的反向事实；已有消费组从已提交偏移继续。
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setMessageModel(MessageModel.CLUSTERING);
        consumer.setConsumeThreadMin(1); consumer.setConsumeThreadMax(1);
        consumer.setConsumeMessageBatchMaxSize(1); consumer.setPullBatchSize(1);
        consumer.setMaxReconsumeTimes(acceptor
                ? WorkflowReverseContract.DLQ_ACCEPTOR_MAX_RECONSUME_TIMES
                : WorkflowReverseContract.MAX_RECONSUME_TIMES);
        consumer.subscribe(topic, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) ->
                handleOne(binding, group, acceptor, handler, messages, context));
        return consumer;
    }

    /**
     * 恢复单条 RocketMQ 逻辑消息，并按消费结果确认或安排重试。
     */
    static ConsumeConcurrentlyStatus handleOne(
            WorkflowReverseContract.Binding binding, String group, boolean acceptor,
            Function<WorkflowReverseTransportMessage, WorkflowReverseConsumeResult> handler,
            List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        if (messages == null || messages.size() != 1) return retry(acceptor, context);
        try {
            MessageExt message = messages.get(0);
            String logicalTopic = message.getTopic();
            if (acceptor && context != null && context.getMessageQueue() != null
                    && binding.acceptorRetryTopic().equals(context.getMessageQueue().getTopic())) {
                logicalTopic = binding.dlqTopic();
            }
            WorkflowReverseConsumeResult result = handler.apply(new WorkflowReverseTransportMessage(
                    logicalTopic, group, message.getKeys(), message.getTags(), message.getBody(),
                    message.getProperties() == null ? Map.of() : message.getProperties()));
            return result.disposition() == WorkflowReverseConsumeResult.Disposition.ACK
                    ? ConsumeConcurrentlyStatus.CONSUME_SUCCESS : retry(acceptor, context);
        }
        catch (RuntimeException failure) { return retry(acceptor, context); }
    }

    /**
     * 创建或执行受控重试安排，使尚未收敛的处理在后续投递中继续。
     */
    private static ConsumeConcurrentlyStatus retry(boolean acceptor,
                                                    ConsumeConcurrentlyContext context) {
        if (acceptor && context != null) {
            context.setDelayLevelWhenNextConsume(
                    WorkflowReverseContract.DLQ_ACCEPTOR_RETRY_DELAY_LEVEL);
        }
        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
    }
    /**
     * 返回当前消费者的绑定集合，保持主主题与死信路径契约一致。
     */
    private static WorkflowReverseContract.Binding[] bindings() {
        return new WorkflowReverseContract.Binding[]{WorkflowReverseContract.INSTANCE_ESTABLISHED,
                WorkflowReverseContract.APPROVAL_FINALIZED};
    }
    /**
     * 停止后台组件并取消任务或释放资源，存在回调时通知停止完成。
     */
    @Override public synchronized void stop() {
        running.set(false);
        for (DefaultMQPushConsumer consumer : consumers) consumer.shutdown();
        consumers.clear();
    }
    /**
     * 返回运行标记，供 Spring 生命周期和调度分支判断。
     */
    @Override public boolean isRunning() { return running.get(); }
    /**
     * 返回生命周期阶段，决定关联组件启动和停止顺序。
     */
    @Override public int getPhase() { return Integer.MIN_VALUE + 200; }
}
