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

/** Classic RocketMQ consumers for Notification main queues and independent DLQs. */
public final class NotificationRocketMqConsumers implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationRocketMqConsumers.class);

    private final NotificationRocketMqProperties properties;
    private final Function<NotificationTransportMessage, NotificationConsumeResult> mainHandler;
    private final Function<NotificationTransportMessage, NotificationConsumeResult> dlqHandler;
    private final List<DefaultMQPushConsumer> consumers = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean();

    public NotificationRocketMqConsumers(NotificationRocketMqProperties properties,
                                         NotificationServiceAdapter main,
                                         NotificationDlqAdapter dlq) {
        this(properties,
                (Function<NotificationTransportMessage, NotificationConsumeResult>) main::consume,
                (Function<NotificationTransportMessage, NotificationConsumeResult>) dlq::accept);
    }

    NotificationRocketMqConsumers(NotificationRocketMqProperties properties,
                                  Function<NotificationTransportMessage, NotificationConsumeResult> mainHandler,
                                  Function<NotificationTransportMessage, NotificationConsumeResult> dlqHandler) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.mainHandler = Objects.requireNonNull(mainHandler, "mainHandler");
        this.dlqHandler = Objects.requireNonNull(dlqHandler, "dlqHandler");
    }

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
            // DLQ acceptors must be live before main consumers can exhaust into them.
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

    /** Factory variant with explicit nameserver, useful for controlled startup. */
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

    private static ConsumeConcurrentlyStatus retry(boolean acceptor, ConsumeConcurrentlyContext context) {
        if (acceptor && context != null) {
            context.setDelayLevelWhenNextConsume(NotificationContract.DLQ_ACCEPTOR_RETRY_DELAY_LEVEL);
        }
        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
    }

    @Override public synchronized void stop() {
        running.set(false);
        for (DefaultMQPushConsumer consumer : consumers) consumer.shutdown();
        consumers.clear();
    }
    @Override public boolean isRunning() { return running.get(); }
    @Override public int getPhase() { return Integer.MIN_VALUE + 300; }

    @FunctionalInterface public interface NotificationServiceAdapter {
        NotificationConsumeResult consume(NotificationTransportMessage message);
    }
    @FunctionalInterface public interface NotificationDlqAdapter {
        NotificationConsumeResult accept(NotificationTransportMessage message);
    }
}
