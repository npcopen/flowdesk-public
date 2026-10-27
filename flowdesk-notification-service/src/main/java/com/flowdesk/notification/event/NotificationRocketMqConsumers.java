package com.flowdesk.notification.event;

import com.flowdesk.common.notification.NotificationEventContract;
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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** 管理通知主队列和独立死信消费者，将消费结果映射为 Broker 确认或延迟重试。 */
public final class NotificationRocketMqConsumers implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationRocketMqConsumers.class);

    private final NotificationRocketMqProperties properties;
    private final Function<NotificationTransportMessage, NotificationConsumeResult> mainHandler;
    private final Function<NotificationTransportMessage, NotificationConsumeResult> dlqHandler;
    private final List<DefaultMQPushConsumer> consumers = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * 绑定冻结代理配置与主队列、死信两个处理函数，启动时据此建立消费者并直接映射单次处理结果。
     */
    public NotificationRocketMqConsumers(NotificationRocketMqProperties properties,
                                         NotificationServiceAdapter main,
                                         NotificationDlqAdapter dlq) {
        this(properties,
                (Function<NotificationTransportMessage, NotificationConsumeResult>) main::consume,
                (Function<NotificationTransportMessage, NotificationConsumeResult>) dlq::accept);
    }

    /**
     * 绑定冻结代理配置与主队列、死信两个处理函数，启动时据此建立消费者并直接映射单次处理结果。
     */
    NotificationRocketMqConsumers(NotificationRocketMqProperties properties,
                                  Function<NotificationTransportMessage, NotificationConsumeResult> mainHandler,
                                  Function<NotificationTransportMessage, NotificationConsumeResult> dlqHandler) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.mainHandler = Objects.requireNonNull(mainHandler, "mainHandler");
        this.dlqHandler = Objects.requireNonNull(dlqHandler, "dlqHandler");
    }

    /**
     * 启动当前组件并建立消息连接或调度资源，准备处理生命周期内的任务。
     */
    @Override
    public synchronized void start() {
        if (running.get()) return;
        properties.validateEnabledConfiguration();
        RPCHook hook = new AclClientRPCHook(new SessionCredentials(
                properties.getAccessKey(), properties.getSecretKey()));
        try {
            for (NotificationEventContract.Binding binding : NotificationContract.bindings()) {
                consumers.add(create(binding, true, hook, properties.getNameServer(), dlqHandler));
            }
            for (NotificationEventContract.Binding binding : NotificationContract.bindings()) {
                consumers.add(create(binding, false, hook, properties.getNameServer(), mainHandler));
            }
            // 先启动死信接纳器，确保主消费者耗尽重试后已有持久化接收路径。
            for (DefaultMQPushConsumer consumer : consumers) consumer.start();
            running.set(true);
            LOG.info("Notification main consumers and DLQ acceptors are ready");
        }
        catch (Exception failure) {
            for (DefaultMQPushConsumer consumer : consumers) consumer.shutdown();
            consumers.clear();
            throw new IllegalStateException("Notification RocketMQ consumers failed closed", failure);
        }
    }

    /**
     * 按冻结队列绑定创建消息消费者，配置监听器与明确的代理重投边界。
     */
    private static DefaultMQPushConsumer create(NotificationEventContract.Binding binding,
                                                boolean acceptor, RPCHook hook,
                                                String nameServer,
                                                Function<NotificationTransportMessage, NotificationConsumeResult> handler)
            throws Exception {
        String group = acceptor ? binding.dlqAcceptorGroup() : binding.consumerGroup();
        String topic = acceptor ? binding.dlqTopic() : binding.topic();
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(group, hook);
        consumer.setNamesrvAddr(nameServer.trim());
        consumer.setInstanceName("flowdesk-notification-" + binding.source().name().toLowerCase()
                + (acceptor ? "-dlq-" : "-main-") + UUID.randomUUID());
        consumer.setVipChannelEnabled(false);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setMessageModel(MessageModel.CLUSTERING);
        consumer.setConsumeThreadMin(1);
        consumer.setConsumeThreadMax(1);
        consumer.setConsumeMessageBatchMaxSize(1);
        consumer.setPullBatchSize(1);
        consumer.setMaxReconsumeTimes(acceptor
                ? NotificationContract.DLQ_ACCEPTOR_MAX_RECONSUME_TIMES
                : NotificationContract.MAX_RECONSUME_TIMES);
        consumer.subscribe(topic, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) ->
                handleOne(binding, group, acceptor, handler, messages, context));
        return consumer;
    }

    /** 显式指定 NameServer 的消费者工厂，便于受控启动。 */
    static DefaultMQPushConsumer createConsumer(NotificationEventContract.Binding binding,
                                                 boolean acceptor, RPCHook hook,
                                                 String nameServer,
                                                 Function<NotificationTransportMessage, NotificationConsumeResult> handler)
            throws Exception {
        String group = acceptor ? binding.dlqAcceptorGroup() : binding.consumerGroup();
        String topic = acceptor ? binding.dlqTopic() : binding.topic();
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(group, hook);
        consumer.setNamesrvAddr(nameServer.trim());
        consumer.setInstanceName("flowdesk-notification-" + binding.source().name().toLowerCase()
                + (acceptor ? "-dlq-" : "-main-") + UUID.randomUUID());
        consumer.setVipChannelEnabled(false);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setMessageModel(MessageModel.CLUSTERING);
        consumer.setConsumeThreadMin(1); consumer.setConsumeThreadMax(1);
        consumer.setConsumeMessageBatchMaxSize(1); consumer.setPullBatchSize(1);
        consumer.setMaxReconsumeTimes(acceptor ? NotificationContract.DLQ_ACCEPTOR_MAX_RECONSUME_TIMES
                : NotificationContract.MAX_RECONSUME_TIMES);
        consumer.subscribe(topic, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) ->
                handleOne(binding, group, acceptor, handler, messages, context));
        return consumer;
    }

    /**
     * 处理一次代理投递并直接映射确认或重试，避免监听器内部嵌套重试。
     */
    static ConsumeConcurrentlyStatus handleOne(NotificationEventContract.Binding binding,
                                                String group, boolean acceptor,
                                                Function<NotificationTransportMessage, NotificationConsumeResult> handler,
                                                List<MessageExt> messages,
                                                ConsumeConcurrentlyContext context) {
        if (messages == null || messages.size() != 1) return retry(acceptor, context);
        try {
            MessageExt message = messages.get(0);
            String logicalTopic = message.getTopic();
            if (acceptor && context != null && context.getMessageQueue() != null
                    && binding.acceptorRetryTopic().equals(context.getMessageQueue().getTopic())) {
                logicalTopic = binding.dlqTopic();
            }
            NotificationConsumeResult result = handler.apply(new NotificationTransportMessage(
                    logicalTopic, group, message.getKeys(), message.getTags(), message.getBody(),
                    message.getProperties() == null ? Map.of() : message.getProperties()));
            return result != null && result.disposition() == NotificationConsumeResult.Disposition.ACK
                    ? ConsumeConcurrentlyStatus.CONSUME_SUCCESS : retry(acceptor, context);
        }
        catch (RuntimeException unexpected) {
            LOG.warn("Notification listener returned delivery to Broker after an unexpected failure");
            return retry(acceptor, context);
        }
    }

    /**
     * 把消费失败转换为代理重投，死信接纳路径补偿代理继承的重试计数。
     */
    private static ConsumeConcurrentlyStatus retry(boolean acceptor, ConsumeConcurrentlyContext context) {
        if (acceptor && context != null) {
            context.setDelayLevelWhenNextConsume(NotificationContract.DLQ_ACCEPTOR_RETRY_DELAY_LEVEL);
        }
        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
    }

    /**
     * 停止组件并释放连接或调度资源，阻止关闭后继续领取任务。
     */
    @Override public synchronized void stop() {
        running.set(false);
        for (DefaultMQPushConsumer consumer : consumers) consumer.shutdown();
        consumers.clear();
    }
    /**
     * 返回组件运行状态，供 Spring 生命周期管理决定启动和停止行为。
     */
    @Override public boolean isRunning() { return running.get(); }
    /**
     * 返回生命周期阶段，使组件按约定顺序启动并反序停止。
     */
    @Override public int getPhase() { return Integer.MIN_VALUE + 300; }

    /**
     * 主队列业务处理适配契约，使传输监听器能替换业务入口进行边界测试。
     */
    @FunctionalInterface public interface NotificationServiceAdapter {
        /**
         * 将原始主队列投递交给业务消费入口，返回单次处理的确认或重试结果。
         */
        NotificationConsumeResult consume(NotificationTransportMessage message);
    }
    /**
     * 死信接纳适配契约，隔离代理监听与恢复事实持久化，便于验证单次确认行为。
     */
    @FunctionalInterface public interface NotificationDlqAdapter {
        /**
         * 将死信交给业务接纳入口，持久化完成后返回可确认的处理结果。
         */
        NotificationConsumeResult accept(NotificationTransportMessage message);
    }
}
